"""Acceso desde celulares y tablets de la misma red WiFi.

- MoiMoi escucha en todas las interfaces; los pedidos que no vienen de esta misma
  computadora se aceptan solo si "Permitir celulares" está activado en Ajustes.
- Además de HTTP (puerto 4747, lo usa la app de Android) se sirve HTTPS (puerto 4748)
  con un certificado propio: los navegadores de celular solo permiten el motor de audio
  (cambio de velocidad y tono) y compartir archivos en páginas seguras.
"""

from __future__ import annotations

import datetime
import ipaddress
import socket
from pathlib import Path


def is_loopback(host: str | None) -> bool:
    if not host:
        return False
    try:
        return ipaddress.ip_address(host.split("%")[0]).is_loopback
    except ValueError:
        return host in {"localhost", "testclient"}


def lan_addresses() -> list[str]:
    """Direcciones IPv4 privadas de esta computadora (la de la red WiFi primero)."""
    found: list[str] = []
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
            s.connect(("10.255.255.255", 1))
            found.append(s.getsockname()[0])
    except OSError:
        pass
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            found.append(info[4][0])
    except OSError:
        pass
    result = []
    for ip in found:
        try:
            addr = ipaddress.ip_address(ip)
        except ValueError:
            continue
        if addr.is_private and not addr.is_loopback and ip not in result:
            result.append(ip)
    return result


def ensure_certificate(folder: Path) -> tuple[Path, Path] | None:
    """Certificado autofirmado para HTTPS (se crea una vez). None si falta `cryptography`."""
    cert_path, key_path = folder / "moimoi-cert.pem", folder / "moimoi-key.pem"
    ips = lan_addresses()
    if cert_path.exists() and key_path.exists() and _covers(cert_path, ips):
        return cert_path, key_path
    try:
        from cryptography import x509
        from cryptography.hazmat.primitives import hashes, serialization
        from cryptography.hazmat.primitives.asymmetric import ec
        from cryptography.x509.oid import NameOID
    except ImportError:
        return None
    folder.mkdir(parents=True, exist_ok=True)
    key = ec.generate_private_key(ec.SECP256R1())
    name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "MoiMoi")])
    alt_names: list[x509.GeneralName] = [x509.DNSName("localhost"), x509.IPAddress(ipaddress.ip_address("127.0.0.1"))]
    alt_names += [x509.IPAddress(ipaddress.ip_address(ip)) for ip in ips]
    now = datetime.datetime.now(datetime.timezone.utc)
    cert = (
        x509.CertificateBuilder()
        .subject_name(name)
        .issuer_name(name)
        .public_key(key.public_key())
        .serial_number(x509.random_serial_number())
        .not_valid_before(now - datetime.timedelta(days=1))
        .not_valid_after(now + datetime.timedelta(days=3650))
        .add_extension(x509.SubjectAlternativeName(alt_names), critical=False)
        .add_extension(x509.BasicConstraints(ca=False, path_length=None), critical=True)
        .sign(key, hashes.SHA256())
    )
    key_path.write_bytes(key.private_bytes(
        serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()))
    cert_path.write_bytes(cert.public_bytes(serialization.Encoding.PEM))
    return cert_path, key_path


def _covers(cert_path: Path, ips: list[str]) -> bool:
    """¿El certificado incluye las IPs actuales? (si cambió la red se genera otro)."""
    try:
        from cryptography import x509

        cert = x509.load_pem_x509_certificate(cert_path.read_bytes())
        names = cert.extensions.get_extension_for_class(x509.SubjectAlternativeName).value
        covered = {str(ip) for ip in names.get_values_for_type(x509.IPAddress)}
        return all(ip in covered for ip in ips)
    except Exception:  # noqa: BLE001 - certificado ilegible: se regenera
        return False
