import AVFoundation
import CoreImage
import Vision

/// 撮影前のプレビュー用：カメラ映像のコマを時々（1秒に数回）切り抜き、背景を見せる部分のマスクを作る。
///
/// 作品そのものは本物のカメラ映像を表示し、このマスクは「背景をかぶせる場所」を決めるだけに使う。
/// 撮影した写真は今までどおり撮影後に元の解像度で切り抜くので、仕上がりには影響しない。
final class LiveSegmenter: NSObject, AVCaptureVideoDataOutputSampleBufferDelegate {
    /// 背景を見せる部分を白、作品を黒にしたマスク。作品が見つからなければ nil。メインスレッドで呼ぶ。
    var onMask: ((CGImage?) -> Void)?

    private let lock = NSLock()
    private var enabled = false
    private var lastRun: CFTimeInterval = 0
    private let ciContext = CIContext(options: [.cacheIntermediates: false])

    /// 処理する間隔（秒）。端末への負担を抑えるため毎コマは処理しない。
    static let interval: CFTimeInterval = 0.12
    /// 切り抜きに使う縮小画像の長辺。
    static let maxDimension: CGFloat = 512

    var isEnabled: Bool {
        get { lock.withLock { enabled } }
        set { lock.withLock { enabled = newValue } }
    }

    func captureOutput(_ output: AVCaptureOutput, didOutput sampleBuffer: CMSampleBuffer,
                       from connection: AVCaptureConnection) {
        guard isEnabled else { return }
        let now = CACurrentMediaTime()
        guard now - lastRun >= Self.interval, let pixelBuffer = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }
        lastRun = now

        let image = CIImage(cvPixelBuffer: pixelBuffer)
        let scale = min(1, Self.maxDimension / max(image.extent.width, image.extent.height))
        let small = image.transformed(by: CGAffineTransform(scaleX: scale, y: scale))
        guard let frame = ciContext.createCGImage(small, from: small.extent) else { return }

        let mask = Self.backgroundMask(for: frame)
        DispatchQueue.main.async { [weak self] in
            guard let self, self.isEnabled else { return }
            self.onMask?(mask)
        }
    }

    private static func backgroundMask(for frame: CGImage) -> CGImage? {
        let request = VNGenerateForegroundInstanceMaskRequest()
        let handler = VNImageRequestHandler(cgImage: frame, orientation: .up)
        guard (try? handler.perform([request])) != nil,
              let observation = request.results?.first, !observation.allInstances.isEmpty,
              let buffer = try? observation.generateScaledMaskForImage(forInstances: observation.allInstances, from: handler),
              var mask = try? AlphaMask(floatPixelBuffer: buffer)
        else { return nil }
        // 作品＝黒、背景＝白にする（背景画像を白い部分だけ見せる）
        for i in mask.values.indices {
            mask.values[i] = 255 - mask.values[i]
        }
        return mask.makeCGImage()
    }
}
