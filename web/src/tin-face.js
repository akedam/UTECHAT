import { FaceDetector, FilesetResolver } from '@mediapipe/tasks-vision'
// These are classic loaders and binary assets, not entries for Vite's JS dependency pre-bundler.
import simdLoader from '../node_modules/@mediapipe/tasks-vision/wasm/vision_wasm_internal.js?url'
import simdBinary from '../node_modules/@mediapipe/tasks-vision/wasm/vision_wasm_internal.wasm?url'
import fallbackLoader from '../node_modules/@mediapipe/tasks-vision/wasm/vision_wasm_nosimd_internal.js?url'
import fallbackBinary from '../node_modules/@mediapipe/tasks-vision/wasm/vision_wasm_nosimd_internal.wasm?url'

let pending
export function faceDetector() {
  pending ||= (async () => {
    const simd = await FilesetResolver.isSimdSupported()
    return FaceDetector.createFromOptions({
      wasmLoaderPath: simd ? simdLoader : fallbackLoader,
      wasmBinaryPath: simd ? simdBinary : fallbackBinary
    }, {
      baseOptions: { modelAssetPath: import.meta.env.BASE_URL + 'tin-model/blazeface.tflite', delegate: 'CPU' },
      runningMode: 'IMAGE', minDetectionConfidence: .56
    })
  })().catch(failure => { pending = undefined; throw failure })
  return pending
}

export function readFaces(detector, canvas) {
  return detector.detect(canvas).detections.filter(face => face.boundingBox).map(face => {
    const { originX: x, originY: y, width, height } = face.boundingBox
    const eyes = (face.keypoints || []).slice(0, 2).sort((left, right) => left.x - right.x)
    const mouth = face.keypoints?.[3]
    return {
      x, y, width, height,
      mouthX: mouth ? mouth.x * canvas.width : x + width / 2,
      mouthY: mouth ? mouth.y * canvas.height - height * .02 : y + height * .72,
      angle: eyes.length === 2
        ? Math.atan2((eyes[1].y - eyes[0].y) * canvas.height, (eyes[1].x - eyes[0].x) * canvas.width) : 0
    }
  }).filter(face => face.width > 0 && face.height > 0)
}
