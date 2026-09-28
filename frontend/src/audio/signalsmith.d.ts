declare module 'signalsmith-stretch' {
  export interface StretchSchedule {
    output?: number
    active?: boolean
    input?: number
    rate?: number
    semitones?: number
    tonalityHz?: number
    formantSemitones?: number
    formantCompensation?: boolean
    formantBaseHz?: number
    loopStart?: number
    loopEnd?: number
  }

  export interface StretchNode extends AudioWorkletNode {
    inputTime: number
    schedule(options: StretchSchedule): Promise<unknown>
    start(when?: number | StretchSchedule): Promise<unknown>
    stop(when?: number): Promise<unknown>
    addBuffers(buffers: ArrayLike<number>[], transfer?: Transferable[]): Promise<number>
    dropBuffers(toSeconds?: number): Promise<{ start: number; end: number }>
    latency(): Promise<number>
    configure(config: { blockMs?: number; intervalMs?: number; splitComputation?: boolean; preset?: string }): Promise<unknown>
    setUpdateInterval(seconds: number, callback?: (inputTime: number) => void): Promise<unknown>
  }

  export default function SignalsmithStretch(context: BaseAudioContext, options?: AudioWorkletNodeOptions): Promise<StretchNode>
}
