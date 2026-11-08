#!/usr/bin/env python3
"""Export the issued post/channel links for Fastlane and ASC reconciliation."""
import csv
import json
from pathlib import Path
import sys
from urllib.parse import urlencode

manifest = json.loads((Path(__file__).resolve().parents[1] / 'content/fastlane-nov-2026.json').read_text())
writer = csv.DictWriter(sys.stdout, fieldnames=['campaign_id','day','content_id','platform','angle','sequence','campaign_token','shortlink','app_store_url','title','published_post_id','published_post_url','clickable_destination_configuration'])
writer.writeheader()
for post in manifest['posts']:
    for platform in manifest['platforms']:
        token = f'{platform}_{post["angle"]}_{post["sequence"]}'
        writer.writerow({
            'campaign_id': manifest['campaignId'], 'day': post['day'], 'content_id': post.get('contentId',''),
            'platform': platform, 'angle': post['angle'], 'sequence': post['sequence'], 'campaign_token': token,
            'shortlink': f'https://whereday.com/v/{platform}-{post["angle"]}-{post["sequence"]}',
            'app_store_url': f'https://apps.apple.com/app/apple-store/id{manifest["appId"]}?' + urlencode({'pt': manifest['providerToken'], 'ct': token, 'mt': '8'}),
            'title': post['title'],
        })
