#!/usr/bin/env python3
"""Verify the guide's four representations against an independent known trip."""
import csv
from datetime import datetime
import json
from pathlib import Path
import xml.etree.ElementTree as ET

SAMPLES = Path(__file__).resolve().parent.parent / 'public/samples'
GPX = {'g': 'http://www.topografix.com/GPX/1/1'}
KML = {'k': 'http://www.opengis.net/kml/2.2', 'gx': 'http://www.google.com/kml/ext/2.2'}
COORDINATES = [(0., 10.), (.001, 10.001), (.002, 10.002), (.003, 10.003), (.004, 10.004)]
TIMES = ['2000-01-01T12:00:00Z', '2000-01-01T12:01:00Z', '2000-01-01T12:02:00Z', '2000-01-01T12:13:00Z', '2000-01-01T12:14:00Z']


def times_equal(values):
    return [datetime.fromisoformat(t.replace('Z', '+00:00')) for t in values] == [datetime.fromisoformat(t.replace('Z', '+00:00')) for t in TIMES]


def check():
    gpx = ET.parse(SAMPLES / 'synthetic-format-trip.gpx').getroot()
    segments = gpx.findall('g:trk/g:trkseg', GPX)
    assert [len(s) for s in segments] == [3, 2], 'GPX boundaries'
    points = [p for s in segments for p in s.findall('g:trkpt', GPX)]
    assert [(float(p.attrib['lat']), float(p.attrib['lon'])) for p in points] == COORDINATES, 'GPX coordinates'
    assert times_equal([p.find('g:time', GPX).text for p in points]), 'GPX times'
    assert not any(p.find('g:ele', GPX) is not None for p in points), 'invented fixture has no measured elevations'

    collection = json.loads((SAMPLES / 'synthetic-format-trip.geojson').read_text())
    assert collection['type'] == 'FeatureCollection'
    features = collection['features']
    assert [len(f['geometry']['coordinates']) for f in features] == [3, 2], 'GeoJSON boundaries'
    assert all(f['type'] == 'Feature' and f['geometry']['type'] == 'LineString' for f in features)
    assert all(f['properties']['synthetic'] is True for f in features)
    assert [f['properties']['segment'] for f in features] == [1, 2]
    positions = [p for f in features for p in f['geometry']['coordinates']]
    assert all(len(p) == 2 for p in positions), '2D GeoJSON positions'
    assert [(p[1], p[0]) for p in positions] == COORDINATES, 'GeoJSON longitude-first order'
    assert all(len(f['properties']['times']) == len(f['geometry']['coordinates']) for f in features)
    assert times_equal([t for f in features for t in f['properties']['times']]), 'custom GeoJSON times'

    kml = ET.parse(SAMPLES / 'synthetic-format-trip.kml').getroot()
    multi = kml.find('k:Document/k:Placemark/gx:MultiTrack', KML)
    assert multi.find('gx:interpolate', KML).text == '0', 'do not interpolate the break'
    assert multi.find('k:altitudeMode', KML).text == 'clampToGround', 'no measured altitude'
    tracks = multi.findall('gx:Track', KML)
    assert [len(t.findall('gx:coord', KML)) for t in tracks] == [3, 2]
    assert all(len(t.findall('k:when', KML)) == len(t.findall('gx:coord', KML)) for t in tracks)
    coords = [[float(n) for n in c.text.split()] for t in tracks for c in t.findall('gx:coord', KML)]
    assert [(p[1], p[0]) for p in coords] == COORDINATES, 'KML longitude-first order'
    assert all(p[2] == 0 for p in coords), 'ground-clamped placeholder, not elevation'
    assert times_equal([w.text for t in tracks for w in t.findall('k:when', KML)]), 'KML times'

    with (SAMPLES / 'synthetic-format-trip.csv').open(newline='') as file:
        rows = list(csv.DictReader(file))
    assert len(rows) == 5 and all(r['kind'] == 'track' for r in rows)
    assert [r['group'] for r in rows] == ['1', '1', '1', '2', '2'], 'CSV groups'
    assert [r['point'] for r in rows] == ['1', '2', '3', '1', '2'], 'CSV original point numbering'
    assert [(float(r['latitude']), float(r['longitude'])) for r in rows] == COORDINATES, 'CSV coordinates'
    assert times_equal([r['time_utc'] for r in rows]) and times_equal([r['original_time'] for r in rows]), 'CSV time preservation'
    assert all(r['elevation_m'] == '' for r in rows), 'CSV missing elevation is blank'
    print('Format examples verified: five coordinates/timestamps and two groups across GPX, timed KML, GeoJSON and CSV.')


if __name__ == '__main__':
    check()
