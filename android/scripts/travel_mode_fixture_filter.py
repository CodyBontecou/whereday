"""Remove iOS-only outing travel-mode output from actual Swift renderer results.

Android has no travel mode (PARITY_RECONCILIATION.md, November 18), so the outing fixture generators
drop exactly what Swift adds for it before Android compares against the rest:

- CSV: the trailing `travel_mode,travel_mode_source` columns.
- JSON: each outing's `travelMode` (omitted when nil) and `travelModeSource` fields.
- Markdown: `travel_mode`/`travel_mode_source` front matter, a page's `| Mode | … |` summary row and the
  bulk table's trailing Mode column.

Each removal is checked against the shape Swift writes. Anything else raises `TravelModeFilterError`,
so a format change stops fixture generation instead of producing a wrong reference.
"""
import csv
import io
import re

TRAVEL_COLUMNS = ['travel_mode', 'travel_mode_source']
TRAVEL_JSON_FIELDS = ('travelMode', 'travelModeSource')
BULK_TABLE_HEADER = '| Start | End | Title | Duration | Distance | Points | Visits | Notes | Mode |'
BULK_TABLE_CELLS = 9


class TravelModeFilterError(ValueError):
    """The Swift output did not have the expected travel-mode shape."""


def without_travel_columns(text):
    """Drop the trailing travel columns from every CSV record, keeping all other bytes as written."""
    rows = list(csv.reader(io.StringIO(text, newline='')))
    if not rows or rows[0][-2:] != TRAVEL_COLUMNS or any(name in rows[0][:-2] for name in TRAVEL_COLUMNS):
        raise TravelModeFilterError(f'Unexpected outing CSV header: {rows[0] if rows else rows}')
    header_suffix = ',' + ','.join(TRAVEL_COLUMNS) + '\n'
    first_line_end = text.index('\n') + 1
    if not text[:first_line_end].endswith(header_suffix):
        raise TravelModeFilterError('Outing CSV header does not end with the travel columns')
    kept = [text[:first_line_end - len(header_suffix)] + '\n']
    position = first_line_end
    for row in rows[1:]:
        fields = row[-2:]
        if any(ch in field for field in fields for ch in ',"\r\n'):
            raise TravelModeFilterError(f'Unexpected outing CSV travel fields: {fields}')
        suffix = ',' + ','.join(fields) + '\n'
        end = text.find(suffix, position)
        if end < 0:
            raise TravelModeFilterError(f'Outing CSV record does not end with its travel fields: {fields}')
        kept.append(text[position:end] + '\n')
        position = end + len(suffix)
    result = ''.join(kept)
    if position != len(text) or list(csv.reader(io.StringIO(result, newline=''))) != [row[:-2] for row in rows]:
        raise TravelModeFilterError('Outing CSV travel columns could not be removed exactly')
    return result


def without_travel_fields(outing):
    """Drop the travel fields from one exported JSON outing in place."""
    if 'travelModeSource' not in outing:
        raise TravelModeFilterError(f'Outing JSON has no travelModeSource: {sorted(outing)}')
    for field in TRAVEL_JSON_FIELDS:
        outing.pop(field, None)
    return outing


def without_travel_markdown(md):
    """Drop travel front matter, a page's Mode row and the bulk table's Mode column."""
    md, front = re.subn(r'(?m)^travel_mode(?:_source)?: .*\n', '', md)
    md, rows = re.subn(r'(?m)^\| Mode \| .* \|\n', '', md)
    lines = md.split('\n')
    table = False
    tables = 0
    for i, line in enumerate(lines):
        start = line.startswith(BULK_TABLE_HEADER)
        tables += start
        table = start or (table and line.startswith('|'))
        if not table:
            continue
        if line.startswith('|-'):
            cells = line.split('|')
            if len(cells) != BULK_TABLE_CELLS + 2:
                raise TravelModeFilterError(f'Unexpected outing Markdown table separator: {line}')
            lines[i] = '|'.join(cells[:-2] + [''])
        else:
            cells = line[2:-2].split(' | ')
            if len(cells) != BULK_TABLE_CELLS:
                raise TravelModeFilterError(f'Unexpected outing Markdown table row: {line}')
            lines[i] = '| ' + ' | '.join(cells[:-1]) + ' |'
    pages = md.count('\ntype: "outing"\n')
    if front != 2 * pages or rows > pages or tables != ('# Whereday Outings Export' in md):
        raise TravelModeFilterError(
            f'Unexpected outing Markdown travel output: front={front} rows={rows} tables={tables} pages={pages}')
    md = '\n'.join(lines)
    if 'travel_mode' in md or '| Mode |' in md or 'Notes | Mode' in md:
        raise TravelModeFilterError('Outing Markdown travel output left behind')
    return md
