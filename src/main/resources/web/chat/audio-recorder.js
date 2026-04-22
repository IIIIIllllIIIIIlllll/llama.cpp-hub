/**
 * Chat Audio Recorder
 *
 * Uses ScriptProcessorNode for immediate audio capture (zero startup delay),
 * with MediaRecorder as a fallback for browsers that don't support it well.
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
    this.audioContext = null;
    this.mediaStreamSource = null;
    this.scriptNode = null;
    this.mediaRecorder = null;
    this._buffers = [];
    this._bufferSize = 0;
    this.isRecording = false;
    this._lastBlob = null;
    this._startTime = 0;
    this._duration = 0;

    this.autoSplitSeconds = options.autoSplitSeconds || 0;
    this._timer = null;
    this._accumOffset = 0;
  }

  /* ---- public API ---- */

  async start() {
    if (this.isRecording) return;
    try {
      this.stream = await navigator.mediaDevices.getUserMedia({ audio: true });
      const AudioContextClass = window.AudioContext || window.webkitAudioContext;
      this.audioContext = new AudioContextClass();
      this.mediaStreamSource = this.audioContext.createMediaStreamSource(this.stream);

      this._buffers = [];
      this._bufferSize = 0;
      this._startTime = Date.now();

      // ScriptProcessorNode captures audio synchronously from the moment the stream is created
      this.scriptNode = this.audioContext.createScriptProcessor(4096, 1, 1);
      this.scriptNode.onaudioprocess = (e) => {
        const channelData = e.inputBuffer.getChannelData(0);
        const copy = new Float32Array(channelData.length);
        copy.set(channelData);
        this._buffers.push(copy);
        this._bufferSize += copy.length;
      };

      this.mediaStreamSource.connect(this.scriptNode);
      this.scriptNode.connect(this.audioContext.destination);

      this.isRecording = true;

      if (this.autoSplitSeconds > 0) {
        this._accumOffset = 0;
        this._timer = setInterval(() => {
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
    this._duration = (Date.now() - this._startTime) / 1000;

    if (this._timer) {
      clearInterval(this._timer);
      this._timer = null;
    }

    if (this.scriptNode) {
      this.scriptNode.onaudioprocess = null;
      this.mediaStreamSource.disconnect(this.scriptNode);
      this.scriptNode.disconnect(this.audioContext.destination);
    }

    if (this.stream) {
      this.stream.getTracks().forEach((t) => t.stop());
      this.stream = null;
    }

    if (this.audioContext && this.audioContext.state !== 'closed') {
      this.audioContext.close();
      this.audioContext = null;
    }

    this.mediaStreamSource = null;
    this.scriptNode = null;
    this.isRecording = false;
    this._startTime = 0;
    console.log("[AudioRecorder] Recording stopped");

    this._emitAudioBlob();
  }

  /* ---- internal ---- */

  _emitAudioBlob() {
    if (this._buffers.length === 0) return;
    const sampleRate = this.audioContext ? this.audioContext.sampleRate : 44100;

    // For auto-split, only emit the new portion since last emission
    let samples;
    if (this.autoSplitSeconds > 0 && this._accumOffset > 0) {
      if (this._accumOffset >= this._bufferSize) return;
      const available = this._bufferSize - this._accumOffset;
      samples = new Float32Array(available);
      let offset = 0;
      for (const buf of this._buffers) {
        const take = Math.min(buf.length, this._bufferSize - this._accumOffset);
        if (take <= 0) break;
        samples.set(buf, offset);
        offset += take;
        this._accumOffset += buf.length;
      }
    } else {
      const totalLength = this._bufferSize;
      samples = new Float32Array(totalLength);
      let offset = 0;
      for (const buf of this._buffers) {
        samples.set(buf, offset);
        offset += buf.length;
      }
    }

    const blob = encodeWAV(samples, sampleRate);
    this._lastBlob = blob;
    const url = URL.createObjectURL(blob);
    window.dispatchEvent(new CustomEvent("audio-available", {
      detail: { blob, url, duration: this._duration }
    }));
  }
}

function encodeWAV(samples, sampleRate) {
  const numChannels = 1;
  const bitDepth = 16;
  const dataLength = samples.length * (bitDepth / 8);
  const headerLength = 44;
  const totalLength = headerLength + dataLength;
  const buffer = new ArrayBuffer(totalLength);
  const view = new DataView(buffer);

  function writeString(offset, str) {
    for (let i = 0; i < str.length; i++) {
      view.setUint8(offset + i, str.charCodeAt(i));
    }
  }

  writeString(0, 'RIFF');
  view.setUint32(4, totalLength - 8, true);
  writeString(8, 'WAVE');
  writeString(12, 'fmt ');
  view.setUint32(16, 16, true);
  view.setUint16(20, 1, true);
  view.setUint16(22, numChannels, true);
  view.setUint32(24, sampleRate, true);
  view.setUint32(28, sampleRate * numChannels * (bitDepth / 8), true);
  view.setUint16(32, numChannels * (bitDepth / 8), true);
  view.setUint16(34, bitDepth, true);
  writeString(36, 'data');
  view.setUint32(40, dataLength, true);

  for (let i = 0; i < samples.length; i++) {
    const s = Math.max(-1, Math.min(1, samples[i]));
    view.setInt16(44 + i * 2, s < 0 ? s * 0x8000 : s * 0x7FFF, true);
  }

  return new Blob([view], { type: 'audio/wav' });
}

/* ---- shortcut helper: get a Blob from the user once ---- */

async function recordOnce(maxSeconds = 60) {
  const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
  const AudioContextClass = window.AudioContext || window.webkitAudioContext;
  const audioContext = new AudioContextClass();
  const source = audioContext.createMediaStreamSource(stream);
  const scriptNode = audioContext.createScriptProcessor(4096, 1, 1);
  const buffers = [];
  let bufferSize = 0;

  return new Promise((resolve) => {
    scriptNode.onaudioprocess = (e) => {
      const channelData = e.inputBuffer.getChannelData(0);
      const copy = new Float32Array(channelData.length);
      copy.set(channelData);
      buffers.push(copy);
      bufferSize += copy.length;
    };

    source.connect(scriptNode);
    scriptNode.connect(audioContext.destination);

    const stop = () => {
      source.disconnect(scriptNode);
      scriptNode.disconnect(audioContext.destination);
      stream.getTracks().forEach(t => t.stop());
      audioContext.close();

      if (buffers.length === 0) { resolve(null); return; }

      const merged = new Float32Array(bufferSize);
      let offset = 0;
      for (const buf of buffers) {
        merged.set(buf, offset);
        offset += buf.length;
      }

      const sampleRate = audioContext.sampleRate;
      const dataLength = merged.length * 2;
      const wavBuffer = new ArrayBuffer(44 + dataLength);
      const view = new DataView(wavBuffer);

      function writeString(offset, str) {
        for (let i = 0; i < str.length; i++) view.setUint8(offset + i, str.charCodeAt(i));
      }
      writeString(0, 'RIFF');
      view.setUint32(4, 36 + dataLength, true);
      writeString(8, 'WAVE');
      writeString(12, 'fmt ');
      view.setUint32(16, 16, true);
      view.setUint16(20, 1, true);
      view.setUint16(22, 1, true);
      view.setUint32(24, sampleRate, true);
      view.setUint32(28, sampleRate * 2, true);
      view.setUint16(32, 2, true);
      view.setUint16(34, 16, true);
      writeString(36, 'data');
      view.setUint32(40, dataLength, true);

      for (let i = 0; i < merged.length; i++) {
        const s = Math.max(-1, Math.min(1, merged[i]));
        view.setInt16(44 + i * 2, s < 0 ? s * 0x8000 : s * 0x7FFF, true);
      }

      resolve(new Blob([wavBuffer], { type: 'audio/wav' }));
    };

    setTimeout(stop, maxSeconds * 1000);
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
