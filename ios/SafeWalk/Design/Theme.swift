import SwiftUI
import UIKit

// Coral lead, warm neutrals. Text on coral is dark ink: white on #FF8A8A fails contrast.
// Red is kept for Call 911 only; amber and deep orange mark risky stretches.

extension UIColor {
    convenience init(hex: UInt32) {
        self.init(red: CGFloat((hex >> 16) & 0xFF) / 255, green: CGFloat((hex >> 8) & 0xFF) / 255,
                  blue: CGFloat(hex & 0xFF) / 255, alpha: 1)
    }
    static func dynamic(_ light: UInt32, _ dark: UInt32) -> UIColor {
        UIColor { $0.userInterfaceStyle == .dark ? UIColor(hex: dark) : UIColor(hex: light) }
    }
}

enum SW {
    static let coral = Color(UIColor(hex: 0xFF8A8A))
    static let coralInk = Color(UIColor(hex: 0x3B1517))
    /// Coral-deep, for text and icons on light surfaces. #B5403D rather than #E25C5C so small text clears 4.5:1 on white and blush.
    static let accent = Color(UIColor.dynamic(0xB5403D, 0xFF9E9E))
    static let ink = Color(UIColor.dynamic(0x231C1E, 0xF3ECEA))
    static let ink2 = Color(UIColor.dynamic(0x6E6366, 0xB8ADAF))
    /// Decoration only (grabbers, dotted lines): too faint for text.
    static let ink3 = Color(UIColor.dynamic(0xA0979A, 0x8C8385))
    static let paper = Color(UIColor.dynamic(0xFAF6F5, 0x171213))
    static let card = Color(UIColor.dynamic(0xFFFFFF, 0x211A1B))
    static let sand = Color(UIColor.dynamic(0xF2EBEA, 0x342B2C))
    static let line = Color(UIColor.dynamic(0xEDE4E3, 0x3A3133))
    static let blush = Color(UIColor.dynamic(0xFFE3E1, 0x45282A))
    static let blushSoft = Color(UIColor.dynamic(0xFFF4F3, 0x2E2224))
    static let banner = Color(UIColor.dynamic(0x231C1E, 0x3A2F31))
    static let bannerMuted = Color(UIColor(hex: 0xD9CFD1))
    static let emergency = Color(UIColor.dynamic(0xD7263D, 0xFF5C6C))
    static let amber = Color(UIColor(hex: 0xF2A93B))
    static let success = Color(UIColor.dynamic(0x2F9E62, 0x4CC283))

    /// Applies the coral look to the UIKit pieces SwiftUI draws with (segmented controls, nav bars).
    static func configureAppearance() {
        let seg = UISegmentedControl.appearance()
        // sand track, the chosen segment raised in white (card colour in dark mode)
        seg.backgroundColor = UIColor.dynamic(0xF2EBEA, 0x342B2C)
        seg.selectedSegmentTintColor = UIColor.dynamic(0xFFFFFF, 0x211A1B)
        seg.setTitleTextAttributes([.foregroundColor: UIColor.dynamic(0x231C1E, 0xF3ECEA), .font: uiFont(.semibold, 14, .subheadline)], for: .selected)
        seg.setTitleTextAttributes([.foregroundColor: UIColor.dynamic(0x6E6366, 0xB8ADAF), .font: uiFont(.medium, 14, .subheadline)], for: .normal)

        let nav = UINavigationBar.appearance()
        nav.largeTitleTextAttributes = [.font: uiFont(.bold, 32, .largeTitle), .foregroundColor: UIColor.dynamic(0x231C1E, 0xF3ECEA)]
        nav.titleTextAttributes = [.font: uiFont(.bold, 17, .headline), .foregroundColor: UIColor.dynamic(0x231C1E, 0xF3ECEA)]
    }

    /// Plus Jakarta Sans for UIKit, scaled with Dynamic Type like `style`.
    static func uiFont(_ w: Font.Weight, _ size: CGFloat, _ style: UIFont.TextStyle = .body) -> UIFont {
        let base = UIFont(name: Font.jakartaName(w), size: size) ?? .systemFont(ofSize: size)
        return UIFontMetrics(forTextStyle: style).scaledFont(for: base)
    }
}

extension Font {
    static func jakartaName(_ w: Font.Weight) -> String {
        switch w {
        case .bold, .heavy, .black: return "PlusJakartaSans-Bold"
        case .semibold: return "PlusJakartaSans-SemiBold"
        case .medium: return "PlusJakartaSans-Medium"
        default: return "PlusJakartaSans-Regular"
        }
    }

    /// Plus Jakarta Sans, scaled with Dynamic Type relative to `style`.
    static func jakarta(_ size: CGFloat, _ w: Font.Weight = .regular, relativeTo style: Font.TextStyle = .body) -> Font {
        .custom(jakartaName(w), size: size, relativeTo: style)
    }

    static let swTitle = jakarta(22, .bold, relativeTo: .title2)
    static let swHeadline = jakarta(17, .semibold, relativeTo: .headline)
    static let swBody = jakarta(15, .regular, relativeTo: .body)
    static let swButton = jakarta(17, .bold, relativeTo: .headline)
    static let swCaption = jakarta(12, .medium, relativeTo: .caption)
    static let swSection = jakarta(13, .semibold, relativeTo: .footnote)
}

// MARK: - Buttons

/// Coral capsule with dark ink text: the one main action on a screen.
struct CoralButtonStyle: ButtonStyle {
    @Environment(\.isEnabled) private var enabled
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.swButton)
            .foregroundStyle(SW.coralInk)
            .frame(maxWidth: .infinity, minHeight: 56)
            .background(SW.coral.opacity(enabled ? 1 : 0.45), in: Capsule())
            .opacity(configuration.isPressed ? 0.8 : 1)
    }
}

/// Warm grey capsule for the second choice next to a coral button.
struct QuietButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.jakarta(17, .semibold, relativeTo: .headline))
            .foregroundStyle(SW.ink)
            .frame(maxWidth: .infinity, minHeight: 56)
            .background(SW.sand, in: Capsule())
            .opacity(configuration.isPressed ? 0.75 : 1)
    }
}

/// tel: opens the dialer with 911 filled in; the user still taps Call. Red is kept for this button only.
struct Call911Button: View {
    @Environment(\.openURL) private var openURL
    var body: some View {
        Button {
            if let url = URL(string: "tel://911") { openURL(url) }
        } label: {
            Label(L10n.s("call_911"), systemImage: "phone")
                .font(.jakarta(17, .semibold, relativeTo: .headline))
                .foregroundStyle(SW.emergency)
                .frame(maxWidth: .infinity, minHeight: 56)
                .overlay(Capsule().stroke(SW.emergency, lineWidth: 1.5))
                .contentShape(Capsule())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(L10n.s("call_911"))
    }
}

// MARK: - Surfaces

/// White sheet anchored to the bottom of the map, with a grabber.
struct BottomCard<Content: View>: View {
    @ViewBuilder var content: Content
    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Capsule().fill(SW.ink3.opacity(0.5)).frame(width: 36, height: 5)
                .frame(maxWidth: .infinity).padding(.top, 8).padding(.bottom, 16)
                .accessibilityHidden(true)
            content
        }
        .padding(.horizontal, 24)
        .padding(.bottom, 12)
        .background(
            UnevenRoundedRectangleShape(radius: 28).fill(SW.card)
                .shadow(color: .black.opacity(0.12), radius: 16, y: -2)
                .ignoresSafeArea(edges: .bottom)
        )
    }
}

/// Dark banner across the top of the map: next turn while walking, off-route warning.
struct DarkBanner: View {
    let title: String
    let subtitle: String?
    let symbol: String
    let badge: Color
    let badgeInk: Color
    /// 22 gives a circle; the turn badge uses a rounded square.
    var badgeRadius: CGFloat = 22
    var titleFont: Font = .swHeadline

    var body: some View {
        HStack(spacing: 14) {
            Image(systemName: symbol)
                .font(.system(size: 20, weight: .bold))
                .foregroundStyle(badgeInk)
                .frame(width: 44, height: 44)
                .background(badge, in: RoundedRectangle(cornerRadius: badgeRadius, style: .continuous))
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(titleFont).foregroundStyle(.white)
                if let subtitle { Text(subtitle).font(.swCaption).foregroundStyle(SW.bannerMuted) }
            }
            Spacer(minLength: 0)
        }
        .padding(12)
        .background(SW.banner, in: RoundedRectangle(cornerRadius: 22, style: .continuous))
        .shadow(color: .black.opacity(0.2), radius: 10, y: 4)
        .accessibilityElement(children: .combine)
    }
}

/// Bold title with a grey line under it, used at the top of sheets.
struct SheetTitle: View {
    let title: String
    var subtitle: String? = nil
    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(title).font(.swTitle).foregroundStyle(SW.ink)
            if let subtitle { Text(subtitle).font(.swBody).foregroundStyle(SW.ink2) }
        }
    }
}

/// Rounded square tinted blush, holding an SF Symbol in coral-deep.
struct IconWell: View {
    let symbol: String
    var size: CGFloat = 34
    var body: some View {
        Image(systemName: symbol)
            .font(.system(size: size * 0.45, weight: .semibold))
            .foregroundStyle(SW.accent)
            .frame(width: size, height: size)
            .background(SW.blush, in: RoundedRectangle(cornerRadius: size * 0.3, style: .continuous))
            .accessibilityHidden(true)
    }
}
