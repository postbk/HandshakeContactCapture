# Synthetic speech fixture

`summary-speech.wav` says: "Send the product specifications to Alex next week."
It was generated locally with Windows System.Speech on September 13, 2026,
as 16 kHz mono 16-bit PCM WAV. It contains no microphone or user recording.

The opt-in AndroidSpeechProbeTest sends this synthetic audio to the installed
Android speech provider to verify saved-file transcription. Google's provider
requires microphone permission even for file input; it is granted only to the
disposable verification app. The test never reads user media. Enable with the
`speechProbe=true` instrumentation argument; routine tests skip this provider
integration check because speech services/languages vary and may use a network.
