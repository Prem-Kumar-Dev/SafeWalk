import Foundation

/// One of the model's 12 time slots: weekday or weekend × six 4-hour windows, in Chicago time.
struct TimeSlot: Hashable, Sendable {
    var weekend: Bool
    var window: Int   // 0...5, window × 4 = starting hour

    static let windowLabels = ["12–4 am", "4–8 am", "8 am–12 pm", "12–4 pm", "4–8 pm", "8 pm–12 am"]
    static let chicago = TimeZone(identifier: "America/Chicago")!

    var index: Int { (weekend ? 6 : 0) + window }
    var dayLabel: String { weekend ? "Weekend" : "Weekday" }
    var windowLabel: String { Self.windowLabels[window] }
    var label: String { "\(dayLabel) · \(windowLabel)" }

    /// The slot for a moment in Chicago time. Saturday and Sunday count as the weekend,
    /// matching the pipeline (`dayofweek >= 5`).
    static func at(_ date: Date) -> TimeSlot {
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = chicago
        let weekday = cal.component(.weekday, from: date)   // 1 = Sunday, 7 = Saturday
        let hour = cal.component(.hour, from: date)
        return TimeSlot(weekend: weekday == 1 || weekday == 7, window: hour / 4)
    }

    static var now: TimeSlot { at(Date()) }
}
