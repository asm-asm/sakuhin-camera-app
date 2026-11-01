import SwiftUI

/// 撮影画面。撮った写真は EditSession に入れ、プレビューで「撮り直し / 次へ」を選ぶ。
/// 写真ライブラリからの読み込みはしない（アプリ内カメラのみ）。
struct CameraScreen: View {
    let onNext: () -> Void

    @Environment(EditSession.self) private var session
    @State private var showsSettings = false

    var body: some View {
        Group {
            if let image = session.originalImage {
                CapturedPreview(image: image, onRetake: { session.reset() }, onNext: onNext)
            } else {
                LiveCameraView(onCaptured: { image in
                    session.reset()
                    session.originalImage = image
                })
            }
        }
        .background(Color.black.ignoresSafeArea())
        .navigationTitle("撮影")
        .navigationBarTitleDisplayMode(.inline)
        .toolbarBackground(.black, for: .navigationBar)
        .toolbarColorScheme(.dark, for: .navigationBar)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button {
                    showsSettings = true
                } label: {
                    Image(systemName: "gearshape")
                }
                .accessibilityLabel("設定")
            }
        }
        .sheet(isPresented: $showsSettings) {
            SettingsView()
        }
    }
}

private struct LiveCameraView: View {
    let onCaptured: (CGImage) -> Void

    @Environment(EditSession.self) private var session
    @State private var camera = CameraController()
    @State private var liveBackground: CGImage?
    @State private var showsBackgroundPicker = DemoContent.scene == .picker
    @State private var level = MotionLevel()
    @State private var focusPoint: CGPoint?
    @State private var errorMessage: String?
    @Environment(\.openURL) private var openURL

    var body: some View {
        VStack(spacing: 0) {
            GeometryReader { geometry in
                // 写真は縦 4:3。プレビューは切り取らずに表示するので、同じ枠にガイドを描く
                let photoFrame = ImageFit.rect(for: CGSize(width: 3, height: 4), in: geometry.size)
                ZStack {
                    content(photoFrame: photoFrame)
                }
                .frame(width: geometry.size.width, height: geometry.size.height)
            }
            shutterBar
        }
        .task {
            await camera.start()
            level.start()
        }
        .onDisappear {
            camera.stop()
            level.stop()
        }
        .task(id: liveBackgroundKey) {
            // 選んだ背景（撮影前のプレビュー用に縮小したもの）
            guard session.showsBackgroundWhileShooting else {
                liveBackground = nil
                return
            }
            let library = BackgroundLibrary.shared
            let item = library.item(id: session.composition.backgroundID)
            let adjustments = session.composition.adjustments
            liveBackground = await Task.detached(priority: .userInitiated) {
                Renderer.backgroundPreview(item: item, library: library, canvasWidth: 3024, canvasHeight: 4032,
                                           adjustments: adjustments, originalImage: nil)
            }.value
        }
        .task(id: liveSegmentationEnabled) {
            camera.setLiveBackground(enabled: liveSegmentationEnabled)
        }
        .sheet(isPresented: $showsBackgroundPicker) {
            CameraBackgroundPicker()
        }
        .task(id: focusPoint) {
            guard focusPoint != nil else { return }
            try? await Task.sleep(for: .seconds(1.2))
            focusPoint = nil
        }
        .alert("撮影できませんでした", isPresented: Binding(
            get: { errorMessage != nil },
            set: { if !$0 { errorMessage = nil } }
        )) {
            Button("OK", role: .cancel) {}
        } message: {
            Text(errorMessage ?? "")
        }
    }

    private struct LiveBackgroundKey: Equatable {
        let enabled: Bool
        let backgroundID: String?
        let adjustments: BackgroundAdjustments
    }

    private var liveBackgroundKey: LiveBackgroundKey {
        LiveBackgroundKey(enabled: session.showsBackgroundWhileShooting,
                          backgroundID: session.composition.backgroundID,
                          adjustments: session.composition.adjustments)
    }

    private var liveSegmentationEnabled: Bool {
        session.showsBackgroundWhileShooting && camera.status == .running
    }

    @ViewBuilder
    private func content(photoFrame: CGRect) -> some View {
        switch camera.status {
        case .idle:
            ProgressView()
                .tint(.white)
        case .running:
            CameraPreviewView(camera: camera) { point in
                focusPoint = point
            }
            // 作品の部分は本物のカメラ映像のまま、作品以外に選んだ背景を重ねる（縦向きのときだけ）
            if session.showsBackgroundWhileShooting, level.orientation.isPortrait,
               let background = liveBackground, let mask = camera.liveBackgroundMask {
                Image(decorative: background, scale: 1)
                    .resizable()
                    .scaledToFill()
                    .frame(width: photoFrame.width, height: photoFrame.height)
                    .clipped()
                    .mask {
                        Image(decorative: mask, scale: 1)
                            .resizable()
                            .frame(width: photoFrame.width, height: photoFrame.height)
                            .blur(radius: 1.5)
                            .luminanceToAlpha()
                    }
                    .position(x: photoFrame.midX, y: photoFrame.midY)
                    .allowsHitTesting(false)
            }
            GuideOverlay(photoFrame: photoFrame, reading: level.reading)
                .allowsHitTesting(false)
            if let focusPoint {
                RoundedRectangle(cornerRadius: 4)
                    .stroke(.yellow, lineWidth: 1.5)
                    .frame(width: 72, height: 72)
                    .position(focusPoint)
                    .allowsHitTesting(false)
            }
        case .unauthorized:
            message("カメラへのアクセスが許可されていません。設定アプリで「カメラ」をオンにしてください。") {
                Button("設定を開く") {
                    if let url = URL(string: UIApplication.openSettingsURLString) {
                        openURL(url)
                    }
                }
                .buttonStyle(.borderedProminent)
            }
        case .unavailable:
            message("この端末ではカメラを使えません。") {
                sampleButton
            }
        case .failed(let reason):
            message("カメラを起動できませんでした（\(reason)）") {
                EmptyView()
            }
        }
    }

    private var shutterBar: some View {
        ZStack {
            Button(action: shoot) {
                Circle()
                    .fill(.white)
                    .frame(width: 66, height: 66)
                    .overlay(Circle().stroke(.white, lineWidth: 3).padding(-7))
                    .opacity(camera.isCapturing ? 0.5 : 1)
            }
            .disabled(camera.status != .running || camera.isCapturing)
            .accessibilityLabel("撮影")

            HStack {
                backgroundButton
                Spacer()
                VStack(alignment: .trailing, spacing: 8) {
                    Label(level.orientation.isPortrait ? "縦" : "横",
                          systemImage: level.orientation.isPortrait ? "rectangle.portrait" : "rectangle")
                        .font(.footnote)
                        .foregroundStyle(.white)
                        .accessibilityLabel(level.orientation.isPortrait ? "縦向きで撮影" : "横向きで撮影")
                    sampleButton
                }
            }
            .padding(.horizontal)
        }
        .frame(height: 110)
        .frame(maxWidth: .infinity)
    }

    /// 撮影前に背景を選ぶボタン。選んだ背景のサムネイルを表示する。
    private var backgroundButton: some View {
        Button {
            showsBackgroundPicker = true
        } label: {
            VStack(spacing: 4) {
                ZStack {
                    RoundedRectangle(cornerRadius: 8)
                        .fill(.white.opacity(0.15))
                    if session.showsBackgroundWhileShooting, let liveBackground {
                        Image(decorative: liveBackground, scale: 1)
                            .resizable()
                            .scaledToFill()
                    } else {
                        Image(systemName: "photo.on.rectangle.angled")
                            .font(.system(size: 20))
                            .foregroundStyle(.white)
                    }
                }
                .frame(width: 44, height: 56)
                .clipShape(RoundedRectangle(cornerRadius: 8))
                .overlay(RoundedRectangle(cornerRadius: 8).stroke(.white.opacity(0.8), lineWidth: 1.5))
                Text("背景")
                    .font(.caption2)
                    .foregroundStyle(.white)
            }
        }
        .accessibilityLabel("撮影する背景を選ぶ")
    }

    /// カメラのないシミュレータと開発用ビルドだけで出す、同梱サンプル写真での動作確認。
    @ViewBuilder
    private var sampleButton: some View {
        #if DEBUG || targetEnvironment(simulator)
        Button("サンプル") {
            if let image = SampleImage.load() {
                onCaptured(image)
            }
        }
        .buttonStyle(.bordered)
        .tint(.white)
        #endif
    }

    private func message<Actions: View>(_ text: String, @ViewBuilder actions: () -> Actions) -> some View {
        VStack(spacing: 20) {
            Text(text)
                .foregroundStyle(.white)
                .multilineTextAlignment(.center)
            actions()
        }
        .padding(32)
    }

    private func shoot() {
        Task {
            do {
                let image = try await camera.capture(orientation: level.orientation)
                camera.stop()
                onCaptured(image)
            } catch {
                errorMessage = error.localizedDescription
            }
        }
    }
}

/// 中央ガイド枠と水平ガイド。
private struct GuideOverlay: View {
    let photoFrame: CGRect
    let reading: LevelReading?

    var body: some View {
        let side = min(photoFrame.width, photoFrame.height) * 0.7
        ZStack {
            CornerBrackets()
                .stroke(.white.opacity(0.9), style: StrokeStyle(lineWidth: 2, lineCap: .round))
                .frame(width: side, height: side)
                .position(x: photoFrame.midX, y: photoFrame.midY)
            if let reading {
                LevelView(reading: reading)
                    .position(x: photoFrame.midX, y: photoFrame.midY)
            }
        }
    }
}

private struct CornerBrackets: Shape {
    func path(in rect: CGRect) -> Path {
        let length = min(rect.width, rect.height) * 0.12
        var path = Path()
        let corners: [(CGPoint, CGFloat, CGFloat)] = [
            (CGPoint(x: rect.minX, y: rect.minY), 1, 1),
            (CGPoint(x: rect.maxX, y: rect.minY), -1, 1),
            (CGPoint(x: rect.minX, y: rect.maxY), 1, -1),
            (CGPoint(x: rect.maxX, y: rect.maxY), -1, -1),
        ]
        for (corner, dx, dy) in corners {
            path.move(to: CGPoint(x: corner.x + dx * length, y: corner.y))
            path.addLine(to: corner)
            path.addLine(to: CGPoint(x: corner.x, y: corner.y + dy * length))
        }
        return path
    }
}

/// 撮影直後の確認。
private struct CapturedPreview: View {
    let image: CGImage
    let onRetake: () -> Void
    let onNext: () -> Void

    var body: some View {
        VStack(spacing: 0) {
            Image(decorative: image, scale: 1)
                .resizable()
                .scaledToFit()
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            HStack(spacing: 16) {
                Button("撮り直す", action: onRetake)
                    .buttonStyle(.bordered)
                    .tint(.white)
                Button("次へ", action: onNext)
                    .buttonStyle(.borderedProminent)
            }
            .controlSize(.large)
            .frame(height: 110)
        }
    }
}

/// 撮影前に、カメラ画面に重ねて見せる背景を選ぶ。選んだ背景はそのまま背景画面でも使う。
private struct CameraBackgroundPicker: View {
    @Environment(EditSession.self) private var session
    @Environment(SubscriptionStore.self) private var store
    @Environment(\.dismiss) private var dismiss
    @State private var showsPaywall = false

    private let library = BackgroundLibrary.shared

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    Button {
                        session.showsBackgroundWhileShooting = false
                        dismiss()
                    } label: {
                        Label("背景なし（そのまま撮る）", systemImage: "camera")
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding()
                            .background(RoundedRectangle(cornerRadius: 12).fill(Color(.secondarySystemBackground)))
                    }
                    .buttonStyle(.plain)

                    Text("選んだ背景がカメラ画面に重なって、仕上がりを見ながら撮れます。")
                        .font(.footnote)
                        .foregroundStyle(.secondary)

                    ForEach(library.categories, id: \.self) { category in
                        categorySection(category)
                    }
                }
                .padding()
            }
            .navigationTitle("撮影する背景")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("閉じる") { dismiss() }
                }
            }
            .sheet(isPresented: $showsPaywall) {
                PaywallView()
            }
        }
    }

    /// 元の背景のぼかしは撮影前には作れないので出さない。
    @ViewBuilder
    private func categorySection(_ category: String) -> some View {
        let items = library.items(in: category).filter { $0.kind != .blurredOriginal }
        if !items.isEmpty {
            Text(BackgroundLibrary.displayName(ofCategory: category))
                .font(.headline)
            LazyVGrid(columns: [GridItem(.adaptive(minimum: 66), spacing: 10)], spacing: 12) {
                ForEach(items) { item in
                    BackgroundTile(
                        item: item,
                        isSelected: session.showsBackgroundWhileShooting && session.composition.backgroundID == item.id,
                        isLocked: item.premium && !store.isPremium
                    ) {
                        choose(item)
                    }
                }
            }
        }
    }

    private func choose(_ item: BackgroundItem) {
        if item.premium && !store.isPremium {
            showsPaywall = true
            return
        }
        session.composition.backgroundID = item.id
        session.showsBackgroundWhileShooting = true
        dismiss()
    }
}
