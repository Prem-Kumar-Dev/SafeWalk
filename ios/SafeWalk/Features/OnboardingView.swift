import SwiftUI

/// First launch (spec 01 §1): three intro pages on a coral panel, the disclaimer, then the location reason.
struct OnboardingView: View {
    @ObservedObject var model: AppModel
    let finish: () -> Void
    @State private var page = 0

    var body: some View {
        ZStack {
            SW.paper.ignoresSafeArea()
            switch page {
            case 0...2: intro
            case 3: disclaimer
            default: location
            }
        }
    }

    // MARK: Intro

    private static let titles = ["welcome_1", "welcome_2_title", "welcome_3_title"]
    private static let bodies = ["welcome_1_body", "welcome_2_body", "welcome_3_body"]

    private var intro: some View {
        VStack(alignment: .leading, spacing: 0) {
            VStack(spacing: 8) {
                HStack(spacing: 8) {
                    LogoMark().stroke(SW.coralInk, style: StrokeStyle(lineWidth: 2.5, lineCap: .round, lineJoin: .round))
                        .frame(width: 18, height: 18)
                    Text("Lanternway").font(.jakarta(15, .semibold)).foregroundStyle(SW.coralInk)
                    Spacer()
                    if page < 2 {
                        Button(L10n.s("skip")) { page = 3 }
                            .font(.jakarta(13, .semibold)).foregroundStyle(SW.coralInk)
                    }
                }
                .frame(height: 44)
                IntroIllustration(page: page)
                    .aspectRatio(1.15, contentMode: .fit)
                    .background(SW.blushSoft, in: RoundedRectangle(cornerRadius: 28, style: .continuous))
                    .clipShape(RoundedRectangle(cornerRadius: 28, style: .continuous))
                    .accessibilityHidden(true)
            }
            .padding(.horizontal, 24)
            .padding(.bottom, 28)
            .background(UnevenBottomShape(radius: 40).fill(SW.coral).ignoresSafeArea(edges: .top))

            VStack(alignment: .leading, spacing: 0) {
                HStack(spacing: 6) {
                    ForEach(0..<3) { i in
                        Capsule().fill(i == page ? SW.coral : SW.line).frame(width: i == page ? 20 : 6, height: 6)
                    }
                }
                .padding(.top, 24).padding(.bottom, 16)
                .accessibilityHidden(true)
                Text(L10n.s(Self.titles[page])).font(.jakarta(26, .bold, relativeTo: .title)).foregroundStyle(SW.ink)
                Text(L10n.s(Self.bodies[page])).font(.swBody).foregroundStyle(SW.ink2).padding(.top, 10)
                Spacer()
                Button(L10n.s(page == 2 ? "continue" : "next")) { page += 1 }.buttonStyle(CoralButtonStyle())
                    .padding(.bottom, 8)
            }
            .padding(.horizontal, 24)
        }
    }

    // MARK: Disclaimer

    private var disclaimer: some View {
        VStack(alignment: .leading, spacing: 20) {
            ScrollView {
                VStack(alignment: .leading, spacing: 20) {
                    IconWell(symbol: "info.circle", size: 44).padding(.top, 24)
                    Text(L10n.s("disclaimer_title")).font(.jakarta(26, .bold, relativeTo: .title)).foregroundStyle(SW.ink)
                    VStack(alignment: .leading, spacing: 12) {
                        Text(L10n.disclaimerBody).font(.jakarta(14)).foregroundStyle(SW.ink2)
                        ForEach(["disclaimer_point_1", "disclaimer_point_2", "disclaimer_point_3"], id: \.self) { key in
                            Label {
                                Text(L10n.s(key)).font(.jakarta(14)).foregroundStyle(SW.ink)
                            } icon: {
                                Image(systemName: "checkmark").font(.system(size: 13, weight: .bold)).foregroundStyle(SW.accent)
                            }
                        }
                    }
                    .padding(20)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(SW.card, in: RoundedRectangle(cornerRadius: 22, style: .continuous))
                }
            }
            Button(L10n.disclaimerAccept) {
                UserDefaults.standard.set(true, forKey: "disclaimer.accepted")
                page += 1
            }
            .buttonStyle(CoralButtonStyle())
            .padding(.bottom, 8)
        }
        .padding(.horizontal, 24)
    }

    // MARK: Location

    private var location: some View {
        VStack(alignment: .leading, spacing: 0) {
            ZStack {
                Circle().fill(SW.coral.opacity(0.10)).frame(width: 240, height: 240)
                Circle().fill(SW.coral.opacity(0.18)).frame(width: 160, height: 160)
                Circle().fill(SW.coral.opacity(0.30)).frame(width: 96, height: 96)
                Circle().fill(.white).frame(width: 34, height: 34)
                Circle().fill(SW.coral).frame(width: 24, height: 24)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .accessibilityHidden(true)
            Text(L10n.s("location_title")).font(.jakarta(26, .bold, relativeTo: .title)).foregroundStyle(SW.ink)
            Text(L10n.s("location_body")).font(.swBody).foregroundStyle(SW.ink2).padding(.top, 10)
            Label(L10n.s("location_private"), systemImage: "lock")
                .font(.swCaption).foregroundStyle(SW.ink2)
                .padding(.top, 40).padding(.bottom, 12)
            VStack(spacing: 12) {
                Button(L10n.s("location_allow")) {
                    model.location.requestPermission()
                    finish()
                }
                .buttonStyle(CoralButtonStyle())
                Button(L10n.s("location_not_now")) { finish() }.buttonStyle(QuietButtonStyle())
            }
            .padding(.bottom, 8)
        }
        .padding(.horizontal, 24)
    }
}

/// Grid of blocks with a coral stepped route and the dotted straight line it avoids; page 2 adds times, page 3 a phone.
private struct IntroIllustration: View {
    let page: Int

    var body: some View {
        GeometryReader { g in
            let w = g.size.width, h = g.size.height
            ZStack(alignment: .topLeading) {
                Path { p in
                    let step = w / 6
                    var x = step / 2
                    while x < w { p.move(to: CGPoint(x: x, y: 0)); p.addLine(to: CGPoint(x: x, y: h)); x += step }
                    var y = step / 2
                    while y < h { p.move(to: CGPoint(x: 0, y: y)); p.addLine(to: CGPoint(x: w, y: y)); y += step }
                }
                .stroke(SW.blush, lineWidth: 1.5)

                if page == 2 {
                    let pw = w * 0.36, ph = h * 0.72
                    RoundedRectangle(cornerRadius: 14).fill(.white)
                        .overlay(RoundedRectangle(cornerRadius: 14).stroke(SW.coral, lineWidth: 2.5))
                        .frame(width: pw, height: ph)
                        .position(x: w / 2, y: h / 2)
                    Stairs(from: CGPoint(x: w / 2 - pw * 0.3, y: h / 2 + ph * 0.25), to: CGPoint(x: w / 2 + pw * 0.3, y: h / 2 - ph * 0.2), lineWidth: 4)
                    Image(systemName: "checkmark").font(.system(size: 16, weight: .bold)).foregroundStyle(.white)
                        .frame(width: 40, height: 40)
                        .background(SW.banner, in: RoundedRectangle(cornerRadius: 12))
                        .position(x: w / 2 + pw * 0.45, y: h / 2 + ph * 0.42)
                } else {
                    let a = CGPoint(x: w * 0.18, y: h * 0.82), b = CGPoint(x: w * 0.84, y: h * 0.2)
                    Path { p in p.move(to: a); p.addLine(to: b) }
                        .stroke(SW.ink3, style: StrokeStyle(lineWidth: 3, lineCap: .round, dash: [1, 8]))
                    Stairs(from: a, to: b, lineWidth: 6)
                    if page == 1 {
                        tag("44 min", dark: true).position(x: w * 0.35, y: h * 0.62)
                        tag("42 min", dark: false).position(x: w * 0.78, y: h * 0.4)
                    }
                }
            }
        }
    }

    private func tag(_ text: String, dark: Bool) -> some View {
        Text(text).font(.jakarta(12, .semibold))
            .foregroundStyle(dark ? .white : SW.coralInk)
            .padding(.horizontal, 10).padding(.vertical, 4)
            .background(dark ? SW.banner : Color.white, in: Capsule())
            .overlay(Capsule().stroke(dark ? .clear : SW.coral, lineWidth: 1.5))
    }
}

/// Stepped route from `from` to `to` in three steps: coral line, coral start dot, ink end square.
private struct Stairs: View {
    let from: CGPoint
    let to: CGPoint
    let lineWidth: CGFloat

    var body: some View {
        let dx = (to.x - from.x) / 3, dy = (to.y - from.y) / 3
        ZStack(alignment: .topLeading) {
            Path { p in
                p.move(to: from)
                var c = from
                for _ in 0..<3 {
                    c.y += dy; p.addLine(to: c)
                    c.x += dx; p.addLine(to: c)
                }
            }
            .stroke(SW.coral, style: StrokeStyle(lineWidth: lineWidth, lineCap: .round, lineJoin: .round))
            Circle().fill(SW.coral).overlay(Circle().stroke(.white, lineWidth: lineWidth * 0.4))
                .frame(width: lineWidth * 2, height: lineWidth * 2).position(from)
            RoundedRectangle(cornerRadius: lineWidth * 0.6).fill(SW.banner)
                .frame(width: lineWidth * 2.8, height: lineWidth * 2.8).position(to)
        }
    }
}

/// Small stepped-route mark used next to the app name.
struct LogoMark: Shape {
    func path(in r: CGRect) -> Path {
        Path { p in
            p.move(to: CGPoint(x: r.minX + r.width * 0.1, y: r.minY + r.height * 0.9))
            p.addLine(to: CGPoint(x: r.minX + r.width * 0.1, y: r.minY + r.height * 0.55))
            p.addLine(to: CGPoint(x: r.minX + r.width * 0.5, y: r.minY + r.height * 0.55))
            p.addLine(to: CGPoint(x: r.minX + r.width * 0.5, y: r.minY + r.height * 0.2))
            p.addLine(to: CGPoint(x: r.minX + r.width * 0.9, y: r.minY + r.height * 0.2))
        }
    }
}

/// Bottom-rounded panel shape (iOS 16 has no UnevenRoundedRectangle).
struct UnevenBottomShape: Shape {
    var radius: CGFloat
    func path(in rect: CGRect) -> Path {
        Path(UIBezierPath(roundedRect: rect, byRoundingCorners: [.bottomLeft, .bottomRight],
                          cornerRadii: CGSize(width: radius, height: radius)).cgPath)
    }
}
