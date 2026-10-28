#!/usr/bin/env python3
"""Complete and audit Whereday String Catalogs without touching App Store Connect."""

from __future__ import annotations

import argparse
import concurrent.futures
import dataclasses
import html
import json
import re
import sys
import time
import urllib.parse
import urllib.request
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
CATALOGS = (
    Path("IsoMe/Localizable.xcstrings"),
    Path("IsoMe/InfoPlist.xcstrings"),
    Path("IsoMe/AppShortcuts.xcstrings"),
    Path("IsoMeWidget/Localizable.xcstrings"),
    Path("IsoMeWidget/InfoPlist.xcstrings"),
    Path("IsoMeWatch/Localizable.xcstrings"),
    Path("IsoMeWatch/InfoPlist.xcstrings"),
    Path("IsoMeWatchWidget/Localizable.xcstrings"),
    Path("IsoMeWatchWidget/InfoPlist.xcstrings"),
)
LOCALES = ("ar", "bn", "en", "es", "fr", "hi", "ja", "pl", "pt-BR", "ru", "zh-Hans")
GOOGLE_LOCALES = {
    "ar": "ar",
    "bn": "bn",
    "es": "es",
    "fr": "fr",
    "hi": "hi",
    "ja": "ja",
    "pl": "pl",
    "pt-BR": "pt",
    "ru": "ru",
    "zh-Hans": "zh-CN",
}

PLACEHOLDER_RE = re.compile(
    r"%(?:\d+\$)?[-+ #0']*\d*(?:\.\d+)?(?:hh|h|ll|l|L|z|j|t|q)?[diuoxXfFeEgGaAcCsSp@]"
    r"|\$\{[A-Za-z][A-Za-z0-9_]*\}"
    r"|\{[A-Za-z][A-Za-z0-9_]*\}"
)
PROTECTED_RE = re.compile(
    r"https?://[^\s]+"
    r"|%(?:\d+\$)?[-+ #0']*\d*(?:\.\d+)?(?:hh|h|ll|l|L|z|j|t|q)?[diuoxXfFeEgGaAcCsSp@]"
    r"|\$\{[A-Za-z][A-Za-z0-9_]*\}"
    r"|\{[A-Za-z][A-Za-z0-9_]*\}"
    r"|(?<![A-Za-z0-9_.])(?:Whereday|Apple Watch|Apple Maps|App Store|Live Activity|OwnTracks|Overland|GeoJSON|Markdown|JSON|CSV|GPX|KML|GPS|HTTP|API|URL|YAML|iPhone|iOS|Siri|Shortcuts)(?![A-Za-z0-9_.])"
)
EXACT_EQUIVALENTS = {
    "",
    "Whereday",
    "IsoMe",
    "IsoMeWatch",
    "IsoMeWidgetExtension",
    "IsoMeWatchWidgetExtension",
    "JSON",
    "CSV",
    "GPX",
    "KML",
    "GEOJSON",
    "GeoJSON",
    "HTTP",
    "API",
    "URL",
    "GPS",
    "YAML",
    "OwnTracks",
    "Overland",
    "Markdown",
    "MARKDOWN",
    "WEBHOOK",
    "OWNTRACKS",
    "OVERLAND",
    "Apple Maps",
    "api_key",
    "WHEREDAY",
    "TOKEN",
    "BEARER TOKEN",
    "ENDPOINT",
    "OK",
    "iOS %@",
    "iOS",
    "KM",
    "MI",
    "M",
    "S",
    "••••••••",
}
NATURAL_EQUIVALENTS = {
    *(("pl", value) for value in ("Core Location", "Format", "FORMAT", "Import", "START", "STOP", "STATUS", "X-API-Key", "Plan", "m", "MYSELF.MD")),
    ("es", "MANUAL"),
    ("es", "Manual"),
    ("fr", "%lld PHOTOS"),
    ("fr", "%lld minutes"),
    ("fr", "%lld photos"),
    ("fr", "%lld points"),
    ("fr", "1 minute"),
    ("fr", "1 point"),
    ("fr", "15 minutes"),
    ("fr", "5 minutes"),
    ("fr", "ACTIONS"),
    ("fr", "ALTITUDE"),
    ("fr", "COMPACT"),
    ("fr", "30 minutes"),
    ("fr", "DATE"),
    ("fr", "DISTANCE"),
    ("fr", "Destination"),
    ("fr", "Distance"),
    ("fr", "Distance %@"),
    ("fr", "FORMATS"),
    ("fr", "FORMAT"),
    ("fr", "Format"),
    ("fr", "Formats"),
    ("fr", "Notes"),
    ("fr", "MODE"),
    ("fr", "PHOTO"),
    ("fr", "PHOTOS"),
    ("fr", "POINT"),
    ("fr", "POINTS"),
    ("fr", "Points"),
    ("fr", "Photo"),
    ("fr", "Photo GPS"),
    ("fr", "Photos"),
    ("fr", "SOURCE"),
    ("fr", "TYPE"),
    ("fr", "VERSION"),
    ("pt-BR", "ALTITUDE"),
    ("pt-BR", "MANUAL"),
    ("pt-BR", "Manual"),
    ("pt-BR", "STATUS"),
}

SOURCE_OVERRIDES = {
    ("IsoMe/InfoPlist.xcstrings", "NSLocationAlwaysAndWhenInUseUsageDescription"):
        "Whereday needs always-on location access to automatically record the places you visit throughout the day, even when the app is in the background. This enables visit detection and travel tracking.",
    ("IsoMe/InfoPlist.xcstrings", "NSLocationWhenInUseUsageDescription"):
        "Whereday needs location access to show your current location and record visits when the app is open.",
    ("IsoMe/InfoPlist.xcstrings", "NSPhotoLibraryUsageDescription"):
        "Whereday can connect geotagged photos to your private map and outings. Photo files stay in your Photos library; Whereday stores only local metadata on-device.",
    ("IsoMeWatch/InfoPlist.xcstrings", "NSLocationWhenInUseUsageDescription"):
        "Whereday uses your Apple Watch location to track visits and distance directly on the watch.",
}

MANUAL_OVERRIDES = {
    "Whereday needs always-on location access to automatically record the places you visit throughout the day, even when the app is in the background. This enables visit detection and travel tracking.": {
        "ar": "يحتاج Whereday إلى الوصول الدائم إلى موقعك لتسجيل الأماكن التي تزورها تلقائيًا طوال اليوم، حتى عندما يكون التطبيق في الخلفية. يتيح ذلك اكتشاف الزيارات وتتبع التنقل.",
        "bn": "আপনি সারাদিন যেসব স্থানে যান, অ্যাপটি ব্যাকগ্রাউন্ডে থাকলেও সেগুলো স্বয়ংক্রিয়ভাবে রেকর্ড করতে Whereday-এর সর্বদা লোকেশন অ্যাক্সেস প্রয়োজন। এটি ভিজিট শনাক্তকরণ ও যাত্রাপথ ট্র্যাকিং সক্ষম করে।",
        "es": "Whereday necesita acceso permanente a tu ubicación para registrar automáticamente los lugares que visitas durante el día, incluso cuando la app está en segundo plano. Esto permite detectar visitas y registrar tus desplazamientos.",
        "fr": "Whereday a besoin d’un accès permanent à votre position pour enregistrer automatiquement les lieux que vous visitez au cours de la journée, même lorsque l’app est en arrière-plan. Cela permet de détecter les visites et de suivre vos déplacements.",
        "hi": "पूरे दिन आपके द्वारा देखी गई जगहों को अपने-आप रिकॉर्ड करने के लिए Whereday को हमेशा स्थान की अनुमति चाहिए, भले ही ऐप बैकग्राउंड में हो। इससे विज़िट का पता लगाने और यात्रा को ट्रैक करने की सुविधा मिलती है।",
        "ja": "Wheredayは、アプリがバックグラウンドにあるときも、1日を通して訪れた場所を自動的に記録するために、位置情報への常時アクセスを必要とします。これにより、訪問の検出と移動の記録が可能になります。",
        "pt-BR": "O Whereday precisa de acesso contínuo à sua localização para registrar automaticamente os lugares que você visita ao longo do dia, mesmo quando o app está em segundo plano. Isso permite detectar visitas e acompanhar seus deslocamentos.",
        "ru": "Whereday требуется постоянный доступ к геопозиции, чтобы автоматически записывать места, которые вы посещаете в течение дня, даже когда приложение работает в фоновом режиме. Это позволяет определять посещения и отслеживать перемещения.",
        "zh-Hans": "Whereday 需要始终访问您的位置，以便全天自动记录您到访的地点，即使应用在后台运行也可以。此权限用于检测访问记录和跟踪出行。",
    },
    "Whereday needs location access to show your current location and record visits when the app is open.": {
        "ar": "يحتاج Whereday إلى الوصول إلى موقعك لعرض موقعك الحالي وتسجيل الزيارات عندما يكون التطبيق مفتوحًا.",
        "bn": "অ্যাপ খোলা থাকলে আপনার বর্তমান অবস্থান দেখাতে এবং ভিজিট রেকর্ড করতে Whereday-এর লোকেশন অ্যাক্সেস প্রয়োজন।",
        "es": "Whereday necesita acceso a tu ubicación para mostrar tu posición actual y registrar visitas cuando la app está abierta.",
        "fr": "Whereday a besoin d’accéder à votre position pour afficher votre emplacement actuel et enregistrer les visites lorsque l’app est ouverte.",
        "hi": "ऐप खुला होने पर आपका वर्तमान स्थान दिखाने और विज़िट रिकॉर्ड करने के लिए Whereday को स्थान की अनुमति चाहिए।",
        "ja": "Wheredayは、アプリを開いているときに現在地を表示し、訪問を記録するために位置情報へのアクセスを必要とします。",
        "pt-BR": "O Whereday precisa acessar sua localização para mostrar sua posição atual e registrar visitas quando o app está aberto.",
        "ru": "Whereday требуется доступ к геопозиции, чтобы показывать ваше текущее местоположение и записывать посещения, когда приложение открыто.",
        "zh-Hans": "Whereday 需要访问您的位置，以便在应用打开时显示当前位置并记录访问。",
    },
    "Whereday can connect geotagged photos to your private map and outings. Photo files stay in your Photos library; Whereday stores only local metadata on-device.": {
        "ar": "يمكن لـ Whereday ربط الصور ذات الموقع الجغرافي بخريطتك الخاصة ونزهاتك. تبقى ملفات الصور في مكتبة الصور، ولا يخزن Whereday على الجهاز سوى البيانات الوصفية المحلية.",
        "bn": "Whereday জিওট্যাগ করা ছবি আপনার ব্যক্তিগত মানচিত্র ও আউটিংয়ের সঙ্গে যুক্ত করতে পারে। ছবির ফাইল Photos লাইব্রেরিতেই থাকে; Whereday ডিভাইসে শুধু স্থানীয় মেটাডেটা সংরক্ষণ করে।",
        "es": "Whereday puede vincular las fotos con ubicación a tu mapa privado y a tus salidas. Los archivos permanecen en tu fototeca; Whereday solo guarda metadatos locales en el dispositivo.",
        "fr": "Whereday peut associer les photos géolocalisées à votre carte privée et à vos sorties. Les fichiers restent dans votre photothèque ; Whereday ne stocke que des métadonnées locales sur l’appareil.",
        "hi": "Whereday जियोटैग की गई फ़ोटो को आपके निजी मानचित्र और आउटिंग से जोड़ सकता है। फ़ोटो फ़ाइलें आपकी Photos लाइब्रेरी में ही रहती हैं; Whereday डिवाइस पर केवल स्थानीय मेटाडेटा सहेजता है।",
        "ja": "Wheredayは、位置情報付きの写真をプライベートマップや外出記録に関連付けることができます。写真ファイルは写真ライブラリに残り、Wheredayがデバイス上に保存するのはローカルのメタデータだけです。",
        "pt-BR": "O Whereday pode associar fotos com localização ao seu mapa privado e aos seus passeios. Os arquivos continuam na fototeca; o Whereday armazena no dispositivo apenas metadados locais.",
        "ru": "Whereday может связывать фотографии с геометками с вашей личной картой и прогулками. Файлы остаются в медиатеке «Фото»; Whereday хранит на устройстве только локальные метаданные.",
        "zh-Hans": "Whereday 可以将带有位置信息的照片关联到您的私人地图和出行记录。照片文件仍保留在照片图库中；Whereday 只在设备上存储本地元数据。",
    },
    "Whereday uses your Apple Watch location to track visits and distance directly on the watch.": {
        "ar": "يستخدم Whereday موقع Apple Watch لتتبع الزيارات والمسافة مباشرة على الساعة.",
        "bn": "Whereday আপনার Apple Watch-এর লোকেশন ব্যবহার করে সরাসরি ঘড়িতে ভিজিট ও দূরত্ব ট্র্যাক করে।",
        "es": "Whereday usa la ubicación de tu Apple Watch para registrar visitas y distancia directamente en el reloj.",
        "fr": "Whereday utilise la position de votre Apple Watch pour suivre les visites et la distance directement sur la montre.",
        "hi": "Whereday आपकी Apple Watch की लोकेशन का उपयोग करके सीधे घड़ी पर विज़िट और दूरी ट्रैक करता है।",
        "ja": "WheredayはApple Watchの位置情報を使って、訪問と距離をWatch上で直接記録します。",
        "pt-BR": "O Whereday usa a localização do Apple Watch para registrar visitas e distância diretamente no relógio.",
        "ru": "Whereday использует геопозицию Apple Watch, чтобы записывать посещения и расстояние прямо на часах.",
        "zh-Hans": "Whereday 使用 Apple Watch 的位置在手表上直接记录访问和距离。",
    },
    "ACTIONS": {"fr": "ACTIONS"},
    "Allow": {"hi": "अनुमति दें", "ja": "許可"},
    "AUTH": {"bn": "প্রমাণীকরণ", "es": "AUTENTICACIÓN", "pt-BR": "AUTENTICAÇÃO"},
    "Clear": {"bn": "সাফ করুন"},
    "COORDINATES": {"hi": "निर्देशांक"},
    "ENABLE": {"hi": "सक्षम करें"},
    "END": {"bn": "শেষ"},
    "FORMATS": {"fr": "FORMATS"},
    "FROM": {"ja": "開始"},
    "GPS GLITCHES": {"bn": "GPS ত্রুটি"},
    "IGNORE SHORTER THAN": {"bn": "এর চেয়ে ছোট উপেক্ষা করুন"},
    "INTERVAL": {"ar": "الفاصل الزمني"},
    "MANUAL": {"ar": "يدوي"},
    "MODE": {"bn": "মোড", "ru": "РЕЖИМ"},
    "Notes": {"fr": "Notes"},
    "Limited": {"fr": "Limité", "ru": "Ограниченный"},
    "Not selected": {"bn": "নির্বাচিত নয়"},
    "OUTLIER FLAG": {"fr": "INDICATEUR D’ANOMALIE"},
    "POINTS": {"ar": "النقاط"},
    "Reuse": {"hi": "पुनः उपयोग करें", "ja": "再利用"},
    "SPLIT AFTER": {"hi": "इसके बाद विभाजित करें"},
    "SPEED": {"bn": "গতি"},
    "STOP AFTER": {"bn": "এর পরে বন্ধ করুন", "ja": "次の時間後に停止"},
    "Stop tracking": {"hi": "ट्रैकिंग बंद करें"},
    "SUPPORT": {"ar": "الدعم", "fr": "ASSISTANCE"},
    "TAKEN": {"hi": "लिया गया"},
    "TODAY": {"hi": "आज", "ja": "今日"},
    "TRACKING": {"ar": "التتبع"},
    "%lld MATCHED %@": {"ru": "СОПОСТАВЛЕНО: %1$lld %2$@"},
    "LIVE": {"ru": "АКТИВНО"},
    "Whereday Feedback": {"pt-BR": "Feedback do Whereday"},
    "Whereday Widget": {"es": "Widget de Whereday", "fr": "Widget Whereday"},
    "user": {"es": "usuario"},
    "VISIT": {"hi": "विज़िट"},
    "VISITS": {"hi": "विज़िट"},
    "Yesterday": {"hi": "कल"},
    "Watch face complications": {"zh-Hans": "表盘复杂功能"},
    "Unlock Data Export": {
        "ar": "فتح تصدير البيانات",
        "bn": "ডেটা রপ্তানি আনলক করুন",
        "es": "Desbloquear exportación de datos",
        "fr": "Débloquer l’exportation des données",
        "hi": "डेटा एक्सपोर्ट अनलॉक करें",
        "ja": "データ書き出しを解除",
        "pt-BR": "Desbloquear exportação de dados",
        "ru": "Разблокировать экспорт данных",
        "zh-Hans": "解锁数据导出",
    },
    "Tracking stays free and unlimited.": {
        "ar": "يظل التتبع مجانيًا وغير محدود.",
        "bn": "ট্র্যাকিং বিনামূল্যে ও সীমাহীন থাকবে।",
        "es": "El seguimiento sigue siendo gratuito e ilimitado.",
        "fr": "Le suivi reste gratuit et illimité.",
        "hi": "ट्रैकिंग मुफ़्त और असीमित रहेगी।",
        "ja": "トラッキングは引き続き無料で、制限なく利用できます。",
        "pt-BR": "O rastreamento continua gratuito e ilimitado.",
        "ru": "Отслеживание остаётся бесплатным и без ограничений.",
        "zh-Hans": "跟踪功能仍然免费且不限量。",
    },
    "Export your visits, points, and routes in JSON, CSV, or Markdown. Tracking stays free and unlimited.": {
        "ar": "صدّر زياراتك ونقاطك ومساراتك بتنسيق JSON أو CSV أو Markdown. يظل التتبع مجانيًا وغير محدود.",
        "bn": "আপনার ভিজিট, পয়েন্ট ও রুট JSON, CSV বা Markdown ফরম্যাটে রপ্তানি করুন। ট্র্যাকিং বিনামূল্যে ও সীমাহীন থাকবে।",
        "es": "Exporta tus visitas, puntos y rutas en JSON, CSV o Markdown. El seguimiento sigue siendo gratuito e ilimitado.",
        "fr": "Exportez vos visites, points et itinéraires au format JSON, CSV ou Markdown. Le suivi reste gratuit et illimité.",
        "hi": "अपनी विज़िट, पॉइंट और रूट को JSON, CSV या Markdown में एक्सपोर्ट करें। ट्रैकिंग मुफ़्त और असीमित रहेगी।",
        "ja": "訪問、地点、ルートをJSON、CSV、Markdown形式で書き出せます。トラッキングは引き続き無料で、制限なく利用できます。",
        "pt-BR": "Exporte suas visitas, pontos e rotas em JSON, CSV ou Markdown. O rastreamento continua gratuito e ilimitado.",
        "ru": "Экспортируйте посещения, точки и маршруты в JSON, CSV или Markdown. Отслеживание остаётся бесплатным и без ограничений.",
        "zh-Hans": "将访问记录、位置点和路线导出为 JSON、CSV 或 Markdown。跟踪功能仍然免费且不限量。",
    },
}


@dataclasses.dataclass(frozen=True)
class TranslationTask:
    path: Path
    key: str
    locale: str
    index: int
    source: str


def placeholders(value: str) -> list[str]:
    return sorted(PLACEHOLDER_RE.findall(value))


def is_intentional_equivalent(value: str, locale: str | None = None) -> bool:
    if locale is not None and (locale, value) in NATURAL_EQUIVALENTS:
        return True
    if value in EXACT_EQUIVALENTS or value.startswith(("http://", "https://")):
        return True
    without_placeholders = PLACEHOLDER_RE.sub("", value)
    return re.fullmatch(r"[\s\d.,:;+/–—·•≤-]*(?:min|MIN|PTS|m|h|s|D)?[\s\d.,:;+/–—·•≤-]*", without_placeholders) is not None


def translation_prompt(value: str) -> str:
    ascii_letters = [character for character in value if character.isascii() and character.isalpha()]
    if ascii_letters and all(character.isupper() for character in ascii_letters):
        return value.title()
    return value


def preserve_display_case(source: str, translated: str, locale: str) -> str:
    ascii_letters = [character for character in source if character.isascii() and character.isalpha()]
    if ascii_letters and all(character.isupper() for character in ascii_letters) and locale in {"es", "fr", "pl", "pt-BR", "ru"}:
        return translated.upper()
    return translated


def is_active(entry: dict) -> bool:
    return entry.get("extractionState") != "stale"


def source_values(key: str, entry: dict) -> tuple[str, list[str]]:
    english = entry.setdefault("localizations", {}).get("en", {})
    if "stringSet" in english:
        return "stringSet", list(english["stringSet"].get("values", []))
    unit = english.get("stringUnit") or english.get("variations", {}).get("plural", {}).get("other", {}).get("stringUnit", {})
    value = unit.get("value", key)
    return "stringUnit", [value]


def locale_values(entry: dict, locale: str, kind: str) -> list[str] | None:
    localized = entry.get("localizations", {}).get(locale, {})
    if kind == "stringSet":
        values = localized.get("stringSet", {}).get("values")
        return list(values) if isinstance(values, list) else None
    # A plural catalog entry represents the same source value with count-specific forms.
    unit = localized.get("stringUnit") or localized.get("variations", {}).get("plural", {}).get("other", {}).get("stringUnit", {})
    value = unit.get("value")
    return [value] if isinstance(value, str) else None


def set_locale_values(entry: dict, locale: str, kind: str, values: list[str]) -> None:
    localizations = entry.setdefault("localizations", {})
    if kind == "stringSet":
        localizations[locale] = {"stringSet": {"state": "translated", "values": values}}
    else:
        localizations[locale] = {"stringUnit": {"state": "translated", "value": values[0]}}


def protect(value: str, item_index: int) -> tuple[str, dict[str, str]]:
    del item_index
    pieces: list[str] = []
    cursor = 0
    for match in PROTECTED_RE.finditer(value):
        pieces.append(html.escape(value[cursor:match.start()]))
        pieces.append(f'<span translate="no">{html.escape(match.group(0))}</span>')
        cursor = match.end()
    pieces.append(html.escape(value[cursor:]))
    return "".join(pieces), {}


def google_translate_batch(locale: str, values: list[str]) -> list[str]:
    protected_values: list[str] = []
    replacement_maps: list[dict[str, str]] = []
    for index, value in enumerate(values):
        protected, replacements = protect(value, index)
        protected_values.append(protected)
        replacement_maps.append(replacements)

    endpoint = (
        "https://clients5.google.com/translate_a/t?"
        + urllib.parse.urlencode(
            {"client": "dict-chrome-ex", "sl": "en", "tl": GOOGLE_LOCALES[locale], "format": "html"}
        )
    )
    body = urllib.parse.urlencode([("q", value) for value in protected_values]).encode("utf-8")
    last_error: Exception | None = None
    for attempt in range(5):
        try:
            request = urllib.request.Request(endpoint, data=body, headers={"User-Agent": "Whereday-localization-audit/1"})
            with urllib.request.urlopen(request, timeout=45) as response:
                data = json.load(response)
            parts = data if isinstance(data, list) else []
            if len(parts) != len(values):
                raise ValueError(f"translation batch split into {len(parts)} parts, expected {len(values)}")
            restored: list[str] = []
            for source, part, replacements in zip(values, parts, replacement_maps):
                del replacements
                part = re.sub(r"</?span[^>]*>", "", part)
                part = html.unescape(part)
                if placeholders(source) != placeholders(part):
                    raise ValueError(f"placeholder mismatch after translating {source!r}")
                restored.append(part.strip())
            return restored
        except ValueError:
            raise
        except Exception as error:  # Network and provider failures are retried with bounded backoff.
            last_error = error
            time.sleep(1.5 * (attempt + 1))
    raise RuntimeError(f"translation failed for {locale}: {last_error}")


def translate_resilient(locale: str, values: list[str]) -> list[str]:
    try:
        return google_translate_batch(locale, values)
    except (RuntimeError, ValueError):
        if len(values) == 1:
            raise
        midpoint = len(values) // 2
        return translate_resilient(locale, values[:midpoint]) + translate_resilient(locale, values[midpoint:])


def make_batches(tasks: list[TranslationTask], max_items: int = 18, max_chars: int = 4200) -> list[list[TranslationTask]]:
    batches: list[list[TranslationTask]] = []
    current: list[TranslationTask] = []
    current_chars = 0
    for task in tasks:
        size = len(task.source)
        if current and (len(current) >= max_items or current_chars + size > max_chars):
            batches.append(current)
            current = []
            current_chars = 0
        current.append(task)
        current_chars += size
    if current:
        batches.append(current)
    return batches


def fill(overwrite: bool = False) -> int:
    documents: dict[Path, dict] = {}
    kinds: dict[tuple[Path, str], str] = {}
    sources: dict[tuple[Path, str], list[str]] = {}
    pending: list[TranslationTask] = []

    for relative in CATALOGS:
        path = ROOT / relative
        document = json.loads(path.read_text())
        strings = document.setdefault("strings", {})
        document["strings"] = {key: value for key, value in strings.items() if is_active(value)}
        documents[relative] = document

        for key, entry in document["strings"].items():
            override = SOURCE_OVERRIDES.get((str(relative), key))
            if override is not None:
                entry.setdefault("localizations", {})["en"] = {
                    "stringUnit": {"state": "translated", "value": override}
                }

            kind, values = source_values(key, entry)
            if not values:
                raise RuntimeError(f"{relative}: {key!r} has no English source values")
            kinds[(relative, key)] = kind
            sources[(relative, key)] = values
            if "variations" not in entry["localizations"].get("en", {}):
                set_locale_values(entry, "en", kind, values)

            for locale in LOCALES:
                if locale == "en":
                    continue
                manual_key = values[0] if kind == "stringUnit" else key
                manual = MANUAL_OVERRIDES.get(key, {}).get(locale) or MANUAL_OVERRIDES.get(manual_key, {}).get(locale)
                if manual is not None and kind == "stringUnit":
                    set_locale_values(entry, locale, kind, [manual])
                    continue

                existing = locale_values(entry, locale, kind)
                valid_existing = (
                    existing is not None
                    and len(existing) == len(values)
                    and all(target != "" or source == "" for source, target in zip(values, existing))
                    and all(placeholders(source) == placeholders(target) for source, target in zip(values, existing))
                )
                needs_refresh = valid_existing and any(
                    source == target and not is_intentional_equivalent(source, locale)
                    for source, target in zip(values, existing or [])
                )
                if valid_existing and not needs_refresh and not overwrite:
                    continue

                translated_values = list(existing) if existing is not None and len(existing) == len(values) else [""] * len(values)
                for index, source in enumerate(values):
                    target = translated_values[index]
                    target_is_valid = target != "" and placeholders(source) == placeholders(target)
                    if (
                        target_is_valid
                        and not overwrite
                        and not (source == target and not is_intentional_equivalent(source, locale))
                    ):
                        continue
                    if is_intentional_equivalent(source, locale):
                        translated_values[index] = source
                    else:
                        pending.append(TranslationTask(relative, key, locale, index, source))
                set_locale_values(entry, locale, kind, translated_values)

    by_locale: dict[str, list[TranslationTask]] = {locale: [] for locale in GOOGLE_LOCALES}
    for task in pending:
        by_locale[task.locale].append(task)
    batches = [(locale, batch) for locale, tasks in by_locale.items() for batch in make_batches(tasks)]
    print(f"Translating {len(pending)} missing values in {len(batches)} batches...", flush=True)

    def run_batch(item: tuple[str, list[TranslationTask]]) -> tuple[list[TranslationTask], list[str]]:
        locale, batch = item
        translated = translate_resilient(locale, [translation_prompt(task.source) for task in batch])
        translated = [
            preserve_display_case(task.source, value, locale)
            for task, value in zip(batch, translated)
        ]
        return batch, translated

    completed = 0
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as executor:
        for batch, translated in executor.map(run_batch, batches):
            for task, value in zip(batch, translated):
                entry = documents[task.path]["strings"][task.key]
                kind = kinds[(task.path, task.key)]
                values = locale_values(entry, task.locale, kind)
                assert values is not None
                values[task.index] = value
                set_locale_values(entry, task.locale, kind, values)
            completed += len(batch)
            if completed % 250 < len(batch):
                print(f"  {completed}/{len(pending)}", flush=True)

    for relative, document in documents.items():
        (ROOT / relative).write_text(json.dumps(document, ensure_ascii=False, indent=2) + "\n")
    return audit()


def audit_extracted(build_root: Path) -> int:
    """Check compiler-discovered keys for every app target, including shared sources."""
    targets = {
        "IsoMe": Path("IsoMe/Localizable.xcstrings"),
        "IsoMeWatch": Path("IsoMeWatch/Localizable.xcstrings"),
        "IsoMeWidgetExtension": Path("IsoMeWidget/Localizable.xcstrings"),
        "IsoMeWatchWidgetExtension": Path("IsoMeWatchWidget/Localizable.xcstrings"),
    }
    failures = []
    for target, catalog in targets.items():
        files = list(build_root.glob(f"**/{target}.build/Objects-normal/*/*.stringsdata"))
        if not files:
            failures.append(f"no compiler extraction data for {target}")
            continue
        keys = json.loads((ROOT / catalog).read_text())["strings"]
        missing = set()
        for path in files:
            tables = json.loads(path.read_text()).get("tables", {})
            missing.update(item["key"] for item in tables.get("Localizable", []) if item["key"] not in keys)
        failures.extend(f"missing extracted key {catalog}:{key!r}" for key in sorted(missing))
        print(f"  {target}: {len(files)} extraction files, {len(missing)} missing keys")
    if failures:
        print("\n".join(failures), file=sys.stderr)
        return 1
    print("PASS: all four targets' extracted keys are cataloged")
    return 0


def audit(locales: tuple[str, ...] = LOCALES) -> int:
    failures: list[str] = []
    total = 0
    identical: dict[str, list[str]] = {locale: [] for locale in locales if locale != "en"}
    for relative in CATALOGS:
        document = json.loads((ROOT / relative).read_text())
        for key, entry in document.get("strings", {}).items():
            if not is_active(entry):
                continue
            total += 1
            kind, source = source_values(key, entry)
            for locale in locales:
                target = locale_values(entry, locale, kind)
                label = f"{relative}:{key!r}:{locale}"
                if target is None or len(target) != len(source) or any(value == "" and src != "" for src, value in zip(source, target)):
                    failures.append(f"missing {label}")
                    continue
                for source_value, target_value in zip(source, target):
                    if placeholders(source_value) != placeholders(target_value):
                        failures.append(f"placeholder mismatch {label}")
                    if locale != "en" and source_value == target_value and not is_intentional_equivalent(source_value, locale):
                        identical[locale].append(f"{relative}:{key}")
                plural = entry.get("localizations", {}).get(locale, {}).get("variations", {}).get("plural")
                if plural is not None:
                    if locale == "pl" and not {"one", "few", "many", "other"}.issubset(plural):
                        failures.append(f"missing Polish plural forms {label}")
                    for category, form in plural.items():
                        value = form.get("stringUnit", {}).get("value")
                        if not value or placeholders(source[0]) != placeholders(value):
                            failures.append(f"invalid plural form {label}:{category}")

    print(f"Audited {total} active catalog keys across {len(CATALOGS)} catalogs and {len(locales)} locales.")
    for locale, values in identical.items():
        print(f"  {locale}: {len(values)} non-allowlisted values identical to English")
    if failures:
        print("\n".join(failures[:100]), file=sys.stderr)
        print(f"FAILED: {len(failures)} catalog errors", file=sys.stderr)
        return 1
    print("PASS: no missing translations or placeholder mismatches")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("command", choices=("fill", "audit"))
    parser.add_argument("--overwrite", action="store_true", help="regenerate all non-manual, non-allowlisted translations")
    parser.add_argument("--locale", choices=LOCALES, action="append", help="audit only this locale (repeatable; audit command only)")
    parser.add_argument("--extracted-from", type=Path, help="also audit compiler .stringsdata under this build directory")
    args = parser.parse_args()
    if args.command == "fill":
        if args.locale or args.extracted_from:
            parser.error("--locale and --extracted-from are supported only by audit")
        return fill(overwrite=args.overwrite)
    result = audit(tuple(args.locale) if args.locale else LOCALES)
    if args.extracted_from:
        result = max(result, audit_extracted(args.extracted_from))
    return result


if __name__ == "__main__":
    raise SystemExit(main())
