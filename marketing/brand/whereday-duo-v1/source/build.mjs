import {createRequire} from 'node:module';
import {createHash} from 'node:crypto';
const require=createRequire(new URL('../../whereday-field-notes-v1/package.json',import.meta.url));
const sharp=require('sharp');
import {readFile, writeFile, mkdir} from 'node:fs/promises';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..');
const evidence=path.join(root,'source/screens');
const provenance=JSON.parse(await readFile(path.join(root,'source/capture-manifest.json'),'utf8'));
const uploadReceipt=JSON.parse(await readFile(path.join(root,'upload-receipt.json'),'utf8').catch(()=> 'null'));
const paper='#F8F5EB', ink='#203557';
const cards=[
 {name:'01-day-unfolded',screen:'mapTimeline',title:'Your day, unfolded.',body:'Your map and timeline. Side by side.',w:2853,h:2007},
 {name:'02-places-in-context',screen:'visitDetails',title:'Every place has a story.',body:'See the visit. Keep the map in view.',w:2853,h:2007},
 {name:'03-after-dark',screen:'dark',title:'A quieter view after dark.',body:'Your places and details, in dark mode.',w:2853,h:2007},
 {name:'04-cover-map',screen:'cover',title:'Your day, at a glance.',body:'Your places and routes on the outer display.',w:1398,h:2034}
];
const esc=s=>s.replaceAll('&','&amp;').replaceAll('<','&lt;');
for(const c of cards){
 await mkdir(path.join(root,'exports'),{recursive:true});
 const portrait=c.h>c.w;
 const bg=await sharp(path.join(root,'art/neighborhood.png')).resize(c.w,c.h,{fit:'fill'}).png().toBuffer();
 const sw=portrait?770:1930;
 const source=await readFile(path.join(evidence,c.screen+'.png'));
 const sourceMeta=await sharp(source).metadata();
 if(sourceMeta.width!==c.w||sourceMeta.height!==c.h)throw new Error('Expected native full-resolution capture: '+c.screen);
 const screen=await sharp(source).resize({width:sw,withoutEnlargement:true}).png().toBuffer();
 c.source={file:'source/screens/'+c.screen+'.png',width:sourceMeta.width,height:sourceMeta.height,sha256:createHash('sha256').update(source).digest('hex')};
 const meta=await sharp(screen).metadata();
 const x=Math.round((c.w-sw)/2),y=portrait?600:510;
 const font=portrait?86:142;
 const overlay=`<svg width="${c.w}" height="${c.h}"><text x="${c.w/2}" y="${portrait?105:112}" text-anchor="middle" font-family="Georgia" font-size="${portrait?47:58}" fill="${ink}">Whereday</text><text x="${c.w/2}" y="${portrait?195:204}" text-anchor="middle" font-family="monospace" letter-spacing="5" font-size="${portrait?21:25}" fill="#566272">YOUR PRIVATE LOCATION JOURNAL</text><text x="${c.w/2}" y="${portrait?355:365}" text-anchor="middle" font-family="Georgia" font-size="${font}" fill="${ink}">${esc(c.title)}</text><text x="${c.w/2}" y="${portrait?442:447}" text-anchor="middle" font-family="Helvetica" font-size="${portrait?37:45}" fill="#566272">${esc(c.body)}</text><rect x="${x-10}" y="${y-10}" width="${sw+20}" height="${meta.height+20}" rx="${portrait?43:30}" fill="#203557"/><circle cx="${c.w/2-120}" cy="${c.h-96}" r="6" fill="#A95138"/><text x="${c.w/2-100}" y="${c.h-88}" font-family="monospace" font-size="${portrait?20:23}" letter-spacing="3" fill="#203557">FIELD NOTES</text></svg>`;
 const mask=Buffer.from(`<svg width="${sw}" height="${meta.height}"><rect width="100%" height="100%" rx="${portrait?34:22}" fill="white"/></svg>`);
 const rounded=await sharp(screen).composite([{input:mask,blend:'dest-in'}]).png().toBuffer();
 const file=c.name+'-'+c.w+'x'+c.h+'.png';
 await sharp(bg).composite([{input:Buffer.from(overlay)},{input:rounded,left:x,top:y}]).flatten({background:paper}).removeAlpha().png().toFile(path.join(root,'exports',file));
 c.file='exports/'+file;
 const out=await sharp(path.join(root,c.file)).metadata();
 if(out.width!==c.w||out.height!==c.h||out.hasAlpha)throw new Error('Invalid export '+file);
 c.sha256=createHash('sha256').update(await readFile(path.join(root,c.file))).digest('hex');
 c.verified={dimensions:true,opaque:true,format:out.format,colourspace:out.space};
}
const thumbs=await Promise.all(cards.map(c=>sharp(path.join(root,c.file)).resize(680,500,{fit:'contain',background:paper}).png().toBuffer()));
await mkdir(path.join(root,'review'),{recursive:true});
await sharp({create:{width:1420,height:1060,channels:3,background:'#DDDCD4'}}).composite(thumbs.map((input,i)=>({input,left:20+(i%2)*700,top:20+Math.floor(i/2)*520}))).png().toFile(path.join(root,'review/contact-sheet.png'));
await writeFile(path.join(root,'manifest.json'),JSON.stringify({status:uploadReceipt?'uploaded-to-draft':'ready-for-upload',uploaded:Boolean(uploadReceipt),uploadReceipt:uploadReceipt?'upload-receipt.json':undefined,provenance,specifications:'https://developer.apple.com/help/app-store-connect/reference/app-information/screenshot-specifications',cards},null,2)+'\n');
console.log(cards.map(c=>c.file).join('\n'));
