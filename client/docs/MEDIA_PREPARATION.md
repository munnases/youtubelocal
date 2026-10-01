# Preparing videos for FamilyTube

S2 uses the existing server's progressive MP4 and WebM streams. The live catalog observed on 2026-09-29 contains 45 WebM and 5 MP4 entries. All 50 report `duration: 0` and have no thumbnail URL, so the Android player reads the actual duration from the file. One observed WebM selected AV1 1920×1080 video and Opus audio and played on the Android 17 phone emulator. That does not establish support on the physical phone or TV.

## Inspect before preparing

Run these commands on a machine with FFmpeg installed, using a local copy of each representative original. Keep the originals unchanged. Do not put intermediate files in the server's scanned library.

```powershell
ffprobe -v error -show_entries format=duration,size,format_name -show_entries stream=index,codec_type,codec_name,profile,width,height,r_frame_rate,pix_fmt -of json "input.webm"
```

Check a sample from every codec/resolution combination on the real TV and phones. Record decode and seek results. WebM, MP4, or a filename extension alone does not prove decoder compatibility.

## Prepare a compatible MP4 copy when a device cannot play an original

Use a separate staging directory on the same filesystem as the final destination. Pick a new final filename; never overwrite the original. If the streams are already H.264 video and AAC audio, a remux is enough:

```powershell
ffmpeg -i "input.mp4" -map 0:v:0 -map 0:a:0? -c copy -movflags +faststart "staging\family-video.tmp.mp4"
```

Otherwise, make an H.264 8-bit SDR video with AAC audio. If an original is above the target device's tested limit, add a scale filter chosen from that device's decoder results before publishing.

```powershell
ffmpeg -i "input.webm" -map 0:v:0 -map 0:a:0? -c:v libx264 -preset medium -crf 21 -pix_fmt yuv420p -force_key_frames "expr:gte(t,n_forced*2)" -c:a aac -b:a 160k -movflags +faststart "staging\family-video.tmp.mp4"
ffprobe -v error -show_entries format=duration,size -show_entries stream=codec_name,width,height,pix_fmt -of json "staging\family-video.tmp.mp4"
```

Play and seek through the staged file before publication. Move it to its new final `.mp4` filename only when complete and validated; use a same-volume rename so viewers never see a partial file. Then run the existing server scan. Keep the source file and any existing metadata; the current scanner may show the prepared copy as another catalog item, so organize the served library deliberately and verify the resulting catalog. The S2 implementation has not changed or converted files on the home server.

The server must retain byte-range support. A read-only request against the configured server returned HTTP 206 with `Content-Range: bytes 0-63/43562591` for one WebM. Recheck prepared MP4 range responses before device acceptance.
