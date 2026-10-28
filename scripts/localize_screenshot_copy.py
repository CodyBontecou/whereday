#!/usr/bin/env python3
"""Populate deterministic localized marketing copy for screenshot composition."""

import json
from pathlib import Path

import localization_catalog


ROOT = Path(__file__).resolve().parents[1]
COPY_PATH = ROOT / "screenshots/localized-copy.json"
LOCALE_MAP = {
    "ar-SA": "ar",
    "bn": "bn",
    "es-ES": "es",
    "fr-FR": "fr",
    "hi": "hi",
    "ja": "ja",
    "pl": "pl",
    "pt-BR": "pt-BR",
    "ru": "ru",
    "zh-Hans": "zh-Hans",
}
OVERRIDES = {
    "pl": {
        (0, "title"): "Spokojna mapa Twoich dni",
        (0, "subtitle"): "Automatycznie wykrywaj wizyty i rejestruj dokładne trasy — wszystko na urządzeniu.",
        (1, "title"): "Jeden panel. Pełna kontrola.",
        (1, "subtitle"): "Dostosuj śledzenie, filtry odległości, automatyczne zatrzymywanie i nazwy miejsc.",
        (2, "title"): "Eksport w otwartych formatach.",
        (2, "subtitle"): "Zabierz swoją historię w formacie JSON, CSV, Markdown, GPX, OwnTracks lub Overland.",
        (3, "title"): "Wybierz, co eksportować.",
        (3, "subtitle"): "Filtruj według dat, pory dnia, zakończonych wizyt i czasu trwania.",
        (4, "title"): "Twoje dane. Twój serwer.",
        (4, "subtitle"): "Wysyłaj lokalizację do własnego API na bieżąco lub partiami.",
    },
    "ar-SA": {
        (0, "subtitle"): "اكتشف الزيارات تلقائيًا وسجّل المسارات بالتفصيل — كل ذلك على جهازك.",
        (1, "subtitle"): "اضبط التتبع وفلاتر المسافة والتوقف التلقائي وأسماء المواقع في ثوانٍ.",
        (4, "title"): "بياناتك. خادمك.",
        (4, "subtitle"): "أرسل تحديثات الموقع إلى واجهة API الخاصة بك فورًا أو على دفعات.",
    },
    "bn": {
        (0, "subtitle"): "স্বয়ংক্রিয়ভাবে ভিজিট শনাক্ত করুন এবং বিস্তারিত রুট রেকর্ড করুন — সবকিছু আপনার ডিভাইসেই।",
        (1, "subtitle"): "সেকেন্ডেই ট্র্যাকিং, দূরত্ব ফিল্টার, অটো-স্টপ ও স্থানের নাম ঠিক করুন।",
        (2, "title"): "উন্মুক্ত ফরম্যাটে রপ্তানি করুন",
        (4, "title"): "আপনার ডেটা। আপনার এন্ডপয়েন্ট।",
        (4, "subtitle"): "লোকেশন আপডেট আপনার নিজস্ব API-তে রিয়েল টাইমে বা ব্যাচে পাঠান।",
    },
    "es-ES": {
        (0, "subtitle"): "Detecta visitas automáticamente y registra rutas detalladas, todo en el dispositivo.",
        (1, "subtitle"): "Ajusta el seguimiento, los filtros de distancia, la parada automática y los nombres en segundos.",
        (2, "title"): "Exporta en formatos abiertos.",
        (2, "subtitle"): "Lleva tu historial en JSON, CSV, Markdown, GPX, OwnTracks u Overland.",
        (4, "title"): "Tus datos. Tu endpoint.",
        (4, "subtitle"): "Envía actualizaciones de ubicación a tu propia API en tiempo real o por lotes.",
    },
    "fr-FR": {
        (1, "title"): "Une interface. Contrôle total.",
        (1, "subtitle"): "Réglez le suivi, les filtres de distance, l’arrêt automatique et les noms de lieux en quelques secondes.",
        (2, "subtitle"): "Emportez votre historique en JSON, CSV, Markdown, GPX, OwnTracks ou Overland.",
        (4, "title"): "Vos données. Votre endpoint.",
        (4, "subtitle"): "Envoyez les mises à jour de position à votre propre API en temps réel ou par lots.",
    },
    "hi": {
        (0, "title"): "आपके दिनों का शांत मानचित्र",
        (0, "subtitle"): "विज़िट अपने-आप पहचानें और विस्तृत रूट रिकॉर्ड करें — सब कुछ आपके डिवाइस पर।",
        (1, "subtitle"): "ट्रैकिंग, दूरी फ़िल्टर, ऑटो-स्टॉप और स्थान के नाम कुछ ही सेकंड में सेट करें।",
        (2, "title"): "खुले फ़ॉर्मैट में एक्सपोर्ट करें",
        (2, "subtitle"): "अपना इतिहास JSON, CSV, Markdown, GPX, OwnTracks या Overland में लें।",
        (4, "title"): "आपका डेटा। आपका एंडपॉइंट।",
        (4, "subtitle"): "स्थान अपडेट अपने API पर रीयल टाइम में या बैच में भेजें।",
    },
    "ja": {
        (0, "title"): "日々を静かに描くマップ",
        (0, "subtitle"): "訪問を自動で検出し、詳細なルートを記録。すべてデバイス上で完結します。",
        (1, "title"): "ひとつの画面ですべてを操作",
        (1, "subtitle"): "追跡、距離フィルター、自動停止、場所の名前をすばやく調整できます。",
        (2, "title"): "オープン形式で書き出し",
        (2, "subtitle"): "履歴をJSON、CSV、Markdown、GPX、OwnTracks、Overland形式で保存できます。",
        (3, "title"): "書き出す内容を細かく選択",
        (3, "subtitle"): "日付範囲、時間帯、完了した訪問、滞在時間で絞り込めます。",
        (4, "title"): "あなたのデータ。あなたのエンドポイント。",
        (4, "subtitle"): "位置情報の更新を独自のAPIへリアルタイムまたは一括で送信します。",
    },
    "pt-BR": {
        (0, "subtitle"): "Detecte visitas automaticamente e registre trajetos detalhados — tudo no dispositivo.",
        (2, "subtitle"): "Leve seu histórico em JSON, CSV, Markdown, GPX, OwnTracks ou Overland.",
        (4, "title"): "Seus dados. Seu endpoint.",
        (4, "subtitle"): "Envie atualizações de localização para sua própria API em tempo real ou em lotes.",
    },
    "ru": {
        (1, "title"): "Одна панель. Полный контроль.",
        (2, "title"): "Экспорт в открытых форматах.",
        (2, "subtitle"): "Сохраняйте историю в JSON, CSV, Markdown, GPX, OwnTracks или Overland.",
        (4, "title"): "Ваши данные. Ваш сервер.",
        (4, "subtitle"): "Отправляйте геоданные в собственный API в реальном времени или пакетами.",
    },
    "zh-Hans": {
        (0, "title"): "记录每一天的静谧地图",
        (0, "subtitle"): "自动检测访问并记录详细路线 — 一切都在设备上完成。",
        (1, "title"): "一个面板，全面掌控",
        (1, "subtitle"): "几秒内调整跟踪、距离过滤、自动停止和地点命名。",
        (2, "title"): "导出为开放格式",
        (2, "subtitle"): "将历史记录保存为 JSON、CSV、Markdown、GPX、OwnTracks 或 Overland。",
        (3, "title"): "精确选择导出内容",
        (3, "subtitle"): "按日期范围、时段、已完成访问和持续时间筛选。",
        (4, "title"): "你的数据，你的端点",
        (4, "subtitle"): "将位置更新实时或分批发送到你自己的 API。",
    },
}


def main() -> None:
    copy = json.loads(COPY_PATH.read_text())
    english = copy["en-US"]
    fields = [(index, field, item[field]) for index, item in enumerate(english) for field in ("title", "subtitle")]
    for store_locale, runtime_locale in LOCALE_MAP.items():
        translated = localization_catalog.translate_resilient(runtime_locale, [value for _, _, value in fields])
        items = [{"name": item["name"]} for item in english]
        for (index, field, _), value in zip(fields, translated):
            items[index][field] = value
        for (index, field), value in OVERRIDES.get(store_locale, {}).items():
            items[index][field] = value
        copy[store_locale] = items
    COPY_PATH.write_text(json.dumps(copy, ensure_ascii=False, indent=2) + "\n")


if __name__ == "__main__":
    main()
