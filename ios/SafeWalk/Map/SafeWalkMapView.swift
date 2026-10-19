import CoreLocation
import MapLibre
import SwiftUI
import UIKit

enum MapColors {
    static let brand = UIColor(hex: 0xFF8A8A)       // coral: the chosen route
    static let amber = UIColor(hex: 0xF2A93B)       // elevated-risk stretches
    static let deepOrange = UIColor(hex: 0xD9541E)  // higher-risk stretches
    static let grey = UIColor(hex: 0xA39C9E)        // the route not chosen
    static let ink = UIColor(hex: 0x231C1E)
    static let pin = UIColor(hex: 0xE25C5C)
    static let loop = CLLocationCoordinate2D(latitude: 41.8819, longitude: -87.6278)
}

/// MapLibre map showing only the user's own routes (never a citywide risk layer, spec 01 "Wording rules").
struct SafeWalkMapView: UIViewRepresentable {
    @ObservedObject var model: AppModel
    var dark: Bool

    func makeCoordinator() -> Coordinator { Coordinator(model: model) }

    func makeUIView(context: Context) -> MLNMapView {
        let map = MLNMapView(frame: .zero, styleURL: DataStore.styleURL(dark: dark))
        map.delegate = context.coordinator
        map.setCenter(MapColors.loop, zoomLevel: 13, animated: false)
        map.showsUserLocation = true
        map.compassViewPosition = .topRight
        map.compassViewMargins = CGPoint(x: 12, y: 120)
        map.attributionButtonPosition = .bottomLeft
        map.logoView.isHidden = true      // attribution text is drawn by SwiftUI so it stays visible
        map.minimumZoomLevel = 9
        let press = UILongPressGestureRecognizer(target: context.coordinator, action: #selector(Coordinator.longPress(_:)))
        map.addGestureRecognizer(press)
        context.coordinator.map = map
        context.coordinator.dark = dark
        return map
    }

    func updateUIView(_ map: MLNMapView, context: Context) {
        let c = context.coordinator
        if c.dark != dark {
            c.dark = dark
            c.styleLoaded = false
            map.styleURL = DataStore.styleURL(dark: dark)
        }
        c.sync()
    }

    @MainActor
    final class Coordinator: NSObject, @preconcurrency MLNMapViewDelegate {
        let model: AppModel
        weak var map: MLNMapView?
        var dark = false
        var styleLoaded = false

        private var routeSource: MLNShapeSource?
        private var shownRoutesKey = ""
        private var shownFit = 0
        private var shownRecenter = 0
        private var centredOnUser = false
        private var pins: [MLNPointAnnotation] = []
        private var trackingWalk = false

        init(model: AppModel) { self.model = model }

        func mapView(_ mapView: MLNMapView, didFinishLoading style: MLNStyle) {
            let src = MLNShapeSource(identifier: "routes", shape: nil, options: nil)
            style.addSource(src)

            let other = MLNLineStyleLayer(identifier: "route-other", source: src)
            other.predicate = NSPredicate(format: "role == 'other'")
            other.lineColor = NSExpression(forConstantValue: MapColors.grey)
            other.lineWidth = NSExpression(forConstantValue: 5)
            other.lineOpacity = NSExpression(forConstantValue: 0.8)
            roundEnds(other)
            style.addLayer(other)

            let casing = MLNLineStyleLayer(identifier: "route-casing", source: src)
            casing.predicate = NSPredicate(format: "role == 'chosen'")
            casing.lineColor = NSExpression(forConstantValue: UIColor.white)
            casing.lineWidth = NSExpression(forConstantValue: 9)
            roundEnds(casing)
            style.addLayer(casing)

            // Chosen route, coloured per piece; higher-risk pieces are also dashed so colour isn't the only signal.
            let styles: [(Int, UIColor, Bool)] = [(0, MapColors.brand, false), (1, MapColors.amber, true), (2, MapColors.deepOrange, true)]
            for (band, color, dashed) in styles {
                let l = MLNLineStyleLayer(identifier: "route-chosen-\(band)", source: src)
                l.predicate = NSPredicate(format: "role == 'chosen' AND band == %d", band)
                l.lineColor = NSExpression(forConstantValue: color)
                l.lineWidth = NSExpression(forConstantValue: 6)
                l.lineJoin = NSExpression(forConstantValue: "round")
                if dashed {
                    l.lineDashPattern = NSExpression(forConstantValue: [1.6, 0.8])
                } else {
                    l.lineCap = NSExpression(forConstantValue: "round")
                }
                style.addLayer(l)
            }
            routeSource = src
            styleLoaded = true
            shownRoutesKey = ""
            sync()
        }

        // MARK: Sync

        func sync() {
            guard styleLoaded, let map else { return }
            updateRoutes()
            updatePins(on: map)

            if !centredOnUser, let c = model.location.coordinate, let d = model.data, d.manifest.bounds.contains(c),
               model.screen == .map {
                centredOnUser = true
                map.setCenter(c, zoomLevel: 15, animated: true)
            }
            if model.fitRequest != shownFit, model.comparison != nil {
                shownFit = model.fitRequest
                fitTrip(on: map)
            }
            let walking = model.screen == .walking
            if walking != trackingWalk {
                trackingWalk = walking
                map.setUserTrackingMode(walking ? .followWithHeading : .none, animated: true, completionHandler: nil)
            }
            if model.recenterRequest != shownRecenter {
                shownRecenter = model.recenterRequest
                if walking {
                    map.setUserTrackingMode(.followWithHeading, animated: true, completionHandler: nil)
                } else if let c = model.location.coordinate {
                    map.setCenter(c, zoomLevel: max(map.zoomLevel, 15), animated: true)
                }
            }
        }

        private func updateRoutes() {
            let chosen: RouteResult?, other: RouteResult?
            if model.screen == .walking, let w = model.walk {
                chosen = w.route; other = nil
            } else if let c = model.comparison {
                chosen = model.selectedRoute
                other = c.lowerRisk == nil ? nil : (model.choice == .direct ? c.lowerRisk : c.direct)
            } else {
                chosen = nil; other = nil
            }
            let key = "\(chosen?.edgeList ?? [])|\(chosen?.lengthMetres ?? 0)|\(other?.lengthMetres ?? 0)|\(model.screen)"
            guard key != shownRoutesKey else { return }
            shownRoutesKey = key

            var features: [MLNShape & MLNFeature] = []
            if let o = other {
                let f = MLNPolylineFeature(coordinates: o.polyline, count: UInt(o.polyline.count))
                f.attributes = ["role": "other", "band": 0]
                features.append(f)
            }
            if let ch = chosen {
                let f = MLNPolylineFeature(coordinates: ch.polyline, count: UInt(ch.polyline.count))
                f.attributes = ["role": "chosen", "band": -1]       // casing only
                features.append(f)
                for s in ch.segments where s.coordinates.count > 1 {
                    let g = MLNPolylineFeature(coordinates: s.coordinates, count: UInt(s.coordinates.count))
                    g.attributes = ["role": "chosen", "band": s.band]
                    features.append(g)
                }
            }
            routeSource?.shape = MLNShapeCollectionFeature(shapes: features)
        }

        private func updatePins(on map: MLNMapView) {
            var wanted: [(String, CLLocationCoordinate2D)] = []
            if model.screen != .map || model.end != nil {
                if let s = model.start, !s.isCurrentLocation { wanted.append(("A", s.coordinate)) }
                if let e = model.end { wanted.append(("B", e.coordinate)) }
            }
            if let p = model.droppedPin { wanted.append(("pin", p)) }
            let same = wanted.count == pins.count && zip(wanted, pins).allSatisfy { w, p in
                p.title == w.0 && p.coordinate.latitude == w.1.latitude && p.coordinate.longitude == w.1.longitude
            }
            if same { return }
            map.removeAnnotations(pins)
            pins = wanted.map { k, c in
                let p = MLNPointAnnotation()
                p.coordinate = c
                p.title = k
                return p
            }
            map.addAnnotations(pins)
        }

        private func fitTrip(on map: MLNMapView) {
            guard let c = model.comparison else { return }
            let pts = c.direct.polyline + (c.lowerRisk?.polyline ?? [])
            guard pts.count > 1 else { return }
            let lats = pts.map(\.latitude), lons = pts.map(\.longitude)
            let b = MLNCoordinateBounds(sw: CLLocationCoordinate2D(latitude: lats.min()!, longitude: lons.min()!),
                                        ne: CLLocationCoordinate2D(latitude: lats.max()!, longitude: lons.max()!))
            // Room for the trip fields on top and the route sheet at the bottom.
            map.setVisibleCoordinateBounds(b, edgePadding: UIEdgeInsets(top: 160, left: 40, bottom: 380, right: 40),
                                           animated: true, completionHandler: nil)
        }

        private func roundEnds(_ l: MLNLineStyleLayer) {
            l.lineCap = NSExpression(forConstantValue: "round")
            l.lineJoin = NSExpression(forConstantValue: "round")
        }

        // MARK: Annotations and gestures

        func mapView(_ mapView: MLNMapView, viewFor annotation: MLNAnnotation) -> MLNAnnotationView? {
            guard let p = annotation as? MLNPointAnnotation, let key = p.title else { return nil }
            if let v = mapView.dequeueReusableAnnotationView(withIdentifier: key) { return v }
            return PinView(kind: key)
        }

        func mapView(_ mapView: MLNMapView, annotationCanShowCallout annotation: MLNAnnotation) -> Bool { false }

        @objc func longPress(_ g: UILongPressGestureRecognizer) {
            guard g.state == .began, let map, model.screen != .walking else { return }
            let c = map.convert(g.location(in: map), toCoordinateFrom: map)
            UIImpactFeedbackGenerator(style: .medium).impactOccurred()
            model.droppedPin = c
        }
    }
}

/// Start ("A"), destination ("B") and dropped-pin markers.
final class PinView: MLNAnnotationView {
    init(kind: String) {
        super.init(reuseIdentifier: kind)
        let size: CGFloat = kind == "pin" ? 22 : 28
        frame = CGRect(x: 0, y: 0, width: size, height: size)
        layer.cornerRadius = size / 2
        layer.borderWidth = 3
        layer.borderColor = UIColor.white.cgColor
        layer.shadowOpacity = 0.3
        layer.shadowRadius = 3
        layer.shadowOffset = CGSize(width: 0, height: 1)
        switch kind {
        case "A": backgroundColor = MapColors.ink
        case "B": backgroundColor = MapColors.brand
        default: backgroundColor = MapColors.pin
        }
        if kind != "pin" {
            let label = UILabel(frame: bounds)
            label.text = kind
            label.textAlignment = .center
            label.font = SW.uiFont(.bold, 14)
            label.textColor = kind == "B" ? UIColor(hex: 0x3B1517) : .white
            addSubview(label)
        }
        isAccessibilityElement = true
        accessibilityLabel = kind == "A" ? L10n.s("from") : kind == "B" ? L10n.s("to") : L10n.s("search_dropped_pin")
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) is not supported") }
}
