// 来源：AAswordman/Operit2（AGPL-3.0）plugins/types/v2/stt.d.ts，仅作接口参考，不含实现。
// Generated from operit-plugin-sdk Rust declarations.

import type { SttTranscribeResultData } from "./results";

export namespace Stt {
  export interface TranscribeRequest {
    /**
     * Encoded audio file bytes, such as WAV; raw PCM must first be framed.
     */
    audio: number[];
    fileName: string;
    contentType: string;
    language?: string;
    /**
     * Uses the selected host configuration when omitted.
     */
    configId?: string;
  }

  function transcribe(request: TranscribeRequest): Promise<SttTranscribeResultData>;
}
