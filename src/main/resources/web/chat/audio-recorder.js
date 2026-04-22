/**
 * Chat Audio Recorder
 *
 * Simple microphone recording module using the MediaRecorder API.
 *
 * Usage:
 *   const recorder = new AudioRecorder();
 *   await recorder.start();
 *   recorder.stop(); // triggers "audio-available" event on window
 *
 * Events:
 *   recording-start  – start() succeeded and mic is active
 *   recording-stop   – recording stopped and audio is ready
 *   audio-available  – { blob, url, duration }
 *   error            – { message }
 */

class AudioRecorder {
  constructor(options = {}) {
    this.stream = null;
    this.mediaRecorder = null;
    this.chunks = [];
    this.isRecording = false;
    this._lastBlob = null;  // 保存最后生成的blob，供外部访问

    // Time delimiters: emit an "audio-available" event each time this period passes
    // even if still recording. Set to 0 to disable auto-emission.
    this.autoSplitSeconds = options.autoSplitSeconds || 0;
    this._timer = null;
    this._elapsed = 0;
  }

  /* ---- public API ---- */

  async start() {
    if (this.isRecording) return;
    try {
      this.stream = await navigator.mediaDevices.getUserMedia({ audio: true });
      this.mediaRecorder = new MediaRecorder(this.stream);
      this.chunks = [];
      this._elapsed = 0;

      this.mediaRecorder.ondataavailable = (e) => {
        if (e.data.size > 0) this.chunks.push(e.data);
      };

      this.mediaRecorder.onstop = () => this._emitAudioBlob();
      this.mediaRecorder.onerror = (e) => window.dispatchEvent(new CustomEvent("error", { detail: e }));

      this.mediaRecorder.start(1000);   // collect 1 s chunks for auto-split
      this.isRecording = true;

      if (this.autoSplitSeconds > 0) {
        this._timer = setInterval(() => {
          this._elapsed += this.autoSplitSeconds;
          this._emitAudioBlob();
        }, this.autoSplitSeconds * 1000);
      }

      window.dispatchEvent(new CustomEvent("recording-start"));
      console.log("[AudioRecorder] Recording started");
    } catch (err) {
      console.error("[AudioRecorder] Failed to start:", err);
      window.dispatchEvent(new CustomEvent("error", { detail: err }));
    }
  }

  stop() {
    if (!this.isRecording) return;
    if (this._timer) {
      clearInterval(this._timer);
      this._timer = null;
    }
    if (this.mediaRecorder && this.mediaRecorder.state !== "inactive") {
      this.mediaRecorder.stop();
    }
    if (this.stream) {
      this.stream.getTracks().forEach((t) => t.stop());
      this.stream = null;
    }
    this.isRecording = false;
    this._elapsed = 0;
    console.log("[AudioRecorder] Recording stopped");
  }

  /* ---- internal ---- */

  _emitAudioBlob() {
    if (this.chunks.length === 0) return;
    const blob = new Blob(this.chunks, { type: this.mediaRecorder?.mimeType || "audio/webm" });
    this._lastBlob = blob;  // 保存引用
    const url = URL.createObjectURL(blob);
    window.dispatchEvent(new CustomEvent("audio-available", {
      detail: { blob, url, duration: null }   // duration available after "recording-stop"
    }));
  }
}

/* ---- shortcut helper: get a Blob from the user once ---- */

async function recordOnce(maxSeconds = 60) {
  const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
  const mr = new MediaRecorder(stream);
  return new Promise((resolve, reject) => {
    const chunks = [];
    mr.ondataavailable = (e) => chunks.push(e.data);
    mr.onstop = () => resolve(new Blob(chunks, { type: mr.mimeType }));
    setTimeout(() => { mr.stop(); stream.getTracks().forEach((t) => t.stop()); }, maxSeconds * 1000);
    mr.start();
  });
}

/* ---- Export for multiple environments ---- */

if (typeof module !== "undefined" && module.exports) {
  module.exports = { AudioRecorder, recordOnce };
}
if (typeof window !== "undefined") {
  window.AudioRecorder = AudioRecorder;
  window.recordOnce    = recordOnce;
}
