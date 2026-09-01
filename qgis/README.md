# QGIS styles

Drop-in symbology for GeoJSON exported by the app. Same five RSSI buckets, same colours, as the
handset plot and the KML export — so a session looks identical in the field, in Google Earth, and
in a QGIS print layout.

| File | Apply to |
|---|---|
| `rftest_wifi_rssi_points.qml` | the **Point** sublayer — categorised on `rssi_bucket` |
| `rftest_track_line.qml` | the **LineString** sublayer — neutral grey route |

## Loading a session

1. **Layer → Add Layer → Add Vector Layer**, pick the `.geojson`.
2. QGIS detects mixed geometry and offers sublayers — **add both** the Point and the LineString.
3. For each: right-click the layer → **Properties → Symbology → Style ▾ (bottom left) → Load
   Style…** → the matching `.qml` above.
4. Drag the LineString layer **below** the Point layer so the route sits under the measurements.

## Basemap

Browser panel → **XYZ Tiles → OpenStreetMap** (double-click), then drag it to the bottom of the
Layers panel. If it is not listed, right-click **XYZ Tiles → New Connection**:

```
https://tile.openstreetmap.org/{z}/{x}/{y}.png
```

OSM gives street centrelines. For anything where you need to see the actual building, pavement or
parking layout, **Google Earth with the KML is the better tool** — imagery included, colouring
already applied, no configuration. Use QGIS when you want real GIS work: buffers, spatial joins
against a site boundary or floorplan, fixed-scale print layouts, or several sessions overlaid.

## Note on the colour scale

The buckets are defined once, in `RssiBucket` in the app source, and flow to all three outputs.
If they are ever changed there, regenerate these files — a legend that disagrees with the map is
worse than no legend.
