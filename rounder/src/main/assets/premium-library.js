/* File Gestioner Library — visual-first offline bookshelf. */
(()=>{'use strict';
const bridge=()=>window.AndroidBridge;
const E=(tag,cls,content)=>{const n=document.createElement(tag);if(cls)n.className=cls;
  if(content!==undefined)n.textContent=content;return n;};
const b=(label,cls,fn)=>{const n=E('button',cls,label);n.type='button';
  n.addEventListener('click',fn);return n;};

const iconDraw=(name)=>{
 const ns='http://www.w3.org/2000/svg';
 const svg=document.createElementNS(ns,'svg');
 svg.setAttribute('viewBox','0 0 24 24');
 svg.setAttribute('width','20');svg.setAttribute('height','20');
 svg.setAttribute('fill','none');svg.setAttribute('stroke','currentColor');
 svg.setAttribute('stroke-width','2');svg.setAttribute('stroke-linecap','round');
 svg.setAttribute('stroke-linejoin','round');
 svg.setAttribute('aria-hidden','true');
 const commands={
  back:['M15 18 9 12 15 6'],
  grid:['M3 3h7v7H3z','M14 3h7v7h-7z','M3 14h7v7H3z','M14 14h7v7h-7z'],
  list:['M8 6h13','M8 12h13','M8 18h13','M3 6h.01','M3 12h.01','M3 18h.01'],
  'sort-up':['M12 19V5','M5 12l7-7 7 7'],
  'sort-down':['M12 5v14','M5 12l7 7 7-7']
 };
 for(const d of commands[name]||[]){
  const node=document.createElementNS(ns,'path');
  node.setAttribute('d',d);
  svg.append(node);
 }
 return svg;
};
const root=E('section','fg-library-root fg-lib-hidden');
root.id='fg-library';
const header=E('header','fg-lib-header'),body=E('main','fg-lib-body');
const brand=E('div','fg-lib-brand');
const monogram=E('div','fg-lib-monogram');
const sharedBrand=E('img','fg-lib-brand-image');
sharedBrand.src='asterion-rounder-mark.svg';
sharedBrand.alt='';sharedBrand.draggable=false;
monogram.append(sharedBrand);
const titleGroup=E('div','fg-lib-title-group');
titleGroup.append(E('strong','fg-lib-brand-title','Asterion Rounder'),
                  E('span','fg-lib-brand-subtitle','ASTERION · FILE PLATFORM'));
brand.append(monogram,titleGroup);
const actions=E('div','fg-lib-actions');
const openBtn=b('Open file','fg-lib-primary',()=>bridge()?.pickFile?.());
const collectionBtn=b('Collection','fg-lib-secondary',()=>{
 cloudMode=false;currentFolder='';stack=[];bridge()?.pickLibraryFolder?.();
});
const tbBtn=b('TeraBox','fg-lib-secondary',()=>{
 cloudMode=true;currentFolder='';stack=[];
 if(bridge()?.getTeraboxConfigured?.())show();
 else showTeraboxSettings();
});
tbBtn.title='Secure native TeraBox storage';
const organizeBtn=b('Organize','fg-lib-secondary fg-premium-organize',()=>showOrganizer());
const settingsBtn=b('⋯','fg-lib-icon fg-premium-settings',()=>showTeraboxSettings());
settingsBtn.setAttribute('aria-label','TeraBox connection settings');
const updateBtn=b('Update','fg-vault-update',()=>showUpdater());
updateBtn.setAttribute('aria-label','Check and install Rounder updates');
openBtn.textContent='Open';
openBtn.setAttribute('aria-label','Open a local Android file');
organizeBtn.textContent='Organize';
updateBtn.textContent='Update';
settingsBtn.setAttribute('aria-label','TeraBox settings');
actions.append(openBtn,organizeBtn,updateBtn,settingsBtn);
header.append(brand,actions);
const welcome=E('section','fg-lib-welcome');
const dashboard=E('section','fg-lib-dashboard');
const kicker=E('p','fg-lib-kicker','LOCAL · CLOUD · CONNECTED');
const hero=E('h1','fg-lib-hero','Everything in one place.');
const subtitle=E('p','fg-lib-subtitle',
  'Open and explore your local and TeraBox files without importing them first.');
welcome.append(kicker,hero,subtitle);
const toolbar=E('section','fg-lib-toolbar');
const back=b('','fg-lib-nav-back',()=>navigateBack());
back.append(iconDraw('back'));
back.setAttribute('aria-label','Return to parent folder');
const heading=E('div','fg-lib-collection-title','Library');
const count=E('span','fg-lib-count','');
const headBox=E('div','fg-lib-head-info');headBox.append(heading,count);
const search=E('input','fg-lib-search');
search.type='search';search.placeholder='Search your books';search.autocomplete='off';
search.setAttribute('aria-label','Search your library');
const layout=b('','fg-lib-icon',()=>{
 list=!list;layout.replaceChildren(iconDraw(list?'list':'grid'));render();
});
layout.append(iconDraw('grid'));
layout.setAttribute('aria-label','Toggle grid and list');
const sortButton=b('','fg-lib-sort',()=>showSortMenu());
const setSortTitle=(label,descending)=>{
 sortButton.replaceChildren(E('span','fg-sort-label',label),
   iconDraw(descending?'sort-down':'sort-up'));
};
setSortTitle('Name',false);
sortButton.setAttribute('aria-label','Sort files');
toolbar.append(back,headBox,search,sortButton,layout);
const filters=E('div','fg-lib-filters');
const deck=E('div','fg-lib-grid');
const empty=E('div','fg-lib-empty');
const loading=E('div','fg-lib-loading','Loading your collection…');
const premiumIntro=E('section','fg-premium-intro');
const premiumEyebrow=E('span','fg-premium-eyebrow','ASTERION CLOUD');
const premiumTitle=E('h1','fg-premium-title','Your collection');
const premiumSummary=E('p','fg-premium-summary',
  'Browse your TeraBox library, keep reading, and discover your files.');
premiumIntro.append(premiumEyebrow,premiumTitle,premiumSummary);
const footer=E('div','fg-lib-footer',
  'ASTERION FILES · LOCAL & TERABOX');
body.append(toolbar,filters,loading,deck,empty,footer);
welcome.hidden=true;dashboard.hidden=true;
root.append(header,body);
document.body.insertBefore(root,document.getElementById('busy')||null);
const categories=[
 ['all','All files'],['image','Images'],['affected','Affected previews'],['video','Video'],['audio','Audio'],
 ['book','Books'],['comic','Comics'],['archive','Archives'],['pdf','PDF'],
 ['document','Documents'],['model','3D assets'],['file','Other']
];
let entries=[],list=false,currentFolder='',stack=[],filter='all',phrase='',loaded=false,visible=false,activeUri='';
let cloudMode=true;
// Search local SAF metadata using persistent native SQLite, never the current
// folder's streamed in-memory list. Cloud browsing is left unchanged.
let indexedResults=[],indexedMore=false,indexedOffset=0,indexedPending=false;
let indexedCount=0,indexedBuilding=false,indexedError='';
let indexedToken=0,indexedTimer=0,indexedRefreshTimer=0;
const indexedRefresh=b('↻','fg-lib-icon',()=>{
  indexedPending=true;indexedError='';
  if(cloudMode)bridge()?.refreshTeraboxIndex?.();
  else bridge()?.refreshLibraryIndex?.();
  render();
});
indexedRefresh.title='Refresh the saved file index';
indexedRefresh.setAttribute('aria-label','Refresh search index');
indexedRefresh.hidden=true;
toolbar.append(indexedRefresh);
function localIndexedMode(){return !cloudMode&&!!phrase.trim();}
function cloudIndexedMode(){return cloudMode&&(!!phrase.trim()||filter==='affected');}
function indexedMode(){return localIndexedMode()||cloudIndexedMode();}
function scheduleIndexSearch(offset=0,delay=140){
  if(indexedTimer)clearTimeout(indexedTimer);
  indexedOffset=Math.max(0,offset);
  indexedPending=true;
  const token=++indexedToken;
  indexedTimer=setTimeout(()=>{
    indexedTimer=0;
    if(!indexedMode()||token!==indexedToken)return;
    if(cloudMode)bridge()?.searchTeraboxIndex?.(phrase,filter,indexedOffset,token);
    else bridge()?.searchLibraryIndex?.(phrase,filter,indexedOffset,token);
  },delay);
}
function renderIndexedResults(){
 if(renderTimer){clearTimeout(renderTimer);renderTimer=0;}
 root.querySelectorAll('.fg-lib-pager').forEach(node=>node.remove());
 empty.replaceChildren();
 showVirtualItems(indexedResults,'search',phrase+'|'+filter+'|'+cloudMode+'|'+list);
 loading.classList.add('fg-lib-hidden');deck.classList.remove('fg-lib-hidden');
 empty.classList.remove('fg-lib-hidden');
 indexedRefresh.hidden=false;
 const suffix=indexedBuilding?' · indexing '+indexedCount.toLocaleString()+' files':'';
 count.textContent=indexedResults.length.toLocaleString()+
   (indexedMore?'+':'')+' matches'+suffix+
   (indexedPending?' · loading more…':'');
 if(indexedResults.length===0)
   empty.append(E('strong','',indexedPending?'Searching…':
     indexedError?'Search unavailable':indexedBuilding?'Indexing folders…':'No matches'),
     E('p','',indexedError||
       (indexedBuilding?'The first index scan is still building.':
        'Search covers all descendants of your selected cloud library.')));
}
function receiveIndexedSearch(data){
  if(!data||data.token!==indexedToken||!indexedMode())return;
  if(cloudMode&&data.scope!==bridge()?.getTeraboxSearchScope?.())return;
  if(!cloudMode&&data.scope)return;
  indexedPending=false;
  const next=Array.isArray(data.items)?data.items:[];
  indexedResults=indexedOffset>0?indexedResults.concat(next):next;
  indexedMore=!!data.hasMore;
  indexedCount=Number(data.indexed)||0;
  indexedBuilding=!!data.building;
  indexedError=data.error||'';
  renderIndexedResults();
}
function indexProgress(data){
 if(data&&!data.building&&cloudMode&&!currentFolder&&visible){
   const folders=entries.filter(x=>x.folder&&x.path).slice(0,24).map(x=>x.path);
   if(folders.length)void 0; /* No remote folder mosaic fetch. */
 }
  if(!data)return;
  if(cloudMode){
    if(data.scope!==bridge()?.getTeraboxSearchScope?.())return;
  }else if(data.root!==bridge()?.getLibraryRoot?.())return;
  indexedCount=Number(data.indexed)||0;
  indexedBuilding=!!data.building;
  indexedError=data.error||'';
  if(!indexedMode()||!visible)return;
  if(indexedRefreshTimer)clearTimeout(indexedRefreshTimer);
  indexedRefreshTimer=setTimeout(()=>{
    indexedRefreshTimer=0;
    scheduleIndexSearch(indexedOffset,0);
  },900);
}

let activeVersion=null,scanning=false,pageStart=0,renderTimer=0,lastRender=0;
let imagePumpVersion=0,imagePumpState=null,listingRequest='',operationDialog=null,pendingFolderImageRead=null;
let sortBy='name',sortDescending=false;
const retainedCards=new Map();
let lastVisibleKeys='';
const PAGE=96;
const naturalCollator=new Intl.Collator(undefined,{numeric:true,sensitivity:'base'});
let catalogRevision=0,indexedRevision=-1,indexedKey='',indexedEntries=[];
let collectionCacheKey='';
const entryIds=new Set();
const folderRail=E('section','fg-vault-folderrail');
const virtualTop=E('div','fg-vault-spacer');
const virtualBottom=E('div','fg-vault-spacer');
body.insertBefore(folderRail,deck);
body.insertBefore(virtualTop,deck);
deck.after(virtualBottom);
let virtualMode='',virtualKey='',virtualFolderKey='',virtualDataKey='';
let virtualFiles=[],virtualFolders=[],virtualHasCovers=false,virtualHasVideos=false,virtualHasPortraits=false,folderRegion=null;
let virtualLastStart=-1,virtualLastEnd=-1,virtualLastSig='';
let virtualRowHeight=276,virtualCols=2,virtualScrollTimer=0;
let updaterPanel=null,updaterData=null,updateChecked=false;
const folderPreviewCache=new Map();
const requestedFolderPreviews=new Set();
let managerPanel=null,managerView='moves',managerData=null,managerDupeData=null;
const managerSelected=new Set();
let managerRunning=false,managerMessage='';
const RECENT_KEY='asterion-rounder-recent-v1';
let recents=[],dashboardKey='';
try{const stored=JSON.parse(localStorage.getItem(RECENT_KEY)||'[]');
 if(Array.isArray(stored))recents=stored.filter(x=>x&&typeof x.name==='string').slice(0,24);
}catch(_){}
function recordRecent(item){
 if(item.folder)return;
 const id=item.path||item.uri;if(!id)return;
 recents=[{name:item.name,title:item.title||item.name,uri:item.uri||'',
  path:item.path||'',terabox:!!item.terabox,size:item.size||0,
  modified:item.modified||0,at:Date.now()},
  ...recents.filter(x=>(x.path||x.uri)!==id)].slice(0,24);
 try{localStorage.setItem(RECENT_KEY,JSON.stringify(recents));}catch(_){}
 dashboardKey='';
}
function drawDashboard(){
 dashboard.hidden=true;return;
 if(stack.length||currentFolder){dashboard.hidden=true;return;}
 dashboard.hidden=false;
 const recent=recents.filter(x=>x.terabox===cloudMode).slice(0,8);
 const seen=new Set(recent.map(x=>x.path||x.uri));
 const suggested=entries.slice(-120).filter(x=>!x.folder&&!seen.has(x.path||x.uri))
  .sort((a,b)=>(b.modified||0)-(a.modified||0)).slice(0,8);
 const signature=(cloudMode?'cloud':'local')+'|'+recent.map(x=>x.path||x.uri).join('|')
  +'|'+suggested.map(x=>x.path||x.uri).join('|');
 if(signature===dashboardKey)return;
 dashboardKey=signature;dashboard.replaceChildren();
 dashboard.append(E('strong','fg-home-heading','Welcome back'));
 const quick=E('div','fg-home-quick');
 quick.append(
  b('TeraBox','fg-home-action',()=>{cloudMode=true;currentFolder='';stack=[];show();}),
  b('Local folders','fg-home-action',()=>{cloudMode=false;currentFolder='';stack=[];show();}),
  b('Open file','fg-home-action',()=>bridge()?.pickFile?.()));
 dashboard.append(quick);
 function section(title,items){
  if(!items.length)return;
  const panel=E('section','fg-home-section');
  panel.append(E('strong','fg-home-label',title));
  const strip=E('div','fg-home-strip');
  for(const entry of items){
   const button=b((classify(entry)==='video'?'▶ ':'')+entry.name,'fg-home-recent',()=>{
    openBook(entries.find(x=>(x.path||x.uri)===(entry.path||entry.uri))||entry);
   });
   button.title=entry.name;strip.append(button);
  }
  panel.append(strip);dashboard.append(panel);
 }
 section('Continue reading / watching',recent);
 section('Recently added',suggested);
}
function classify(entry){
 if(entry.folder)return 'folder';
 const x=entry.name.toLowerCase();
 if(/\.(cbz|cbr)$/.test(x))return 'comic';
 if(/\.(zip|7z|rar|tar|gz)$/.test(x))return 'archive';
 if(/\.(epub)$/.test(x))return 'book';
 if(/\.(pdf)$/.test(x))return 'pdf';
 if(/\.(png|jpe?g|webp|avif|gif|bmp)$/.test(x))return 'image';
 if(/\.(mp4|webm|mkv|mov|avi)$/.test(x))return 'video';
 if(/\.(mp3|m4a|flac|wav|ogg|opus)$/.test(x))return 'audio';
 if(/\.(glb|gltf|fbx|obj|vrm|blend|pmx|pmd)$/.test(x))return 'model';
 if(/\.(docx?|xlsx?|csv|txt|md|rtf|odt|ods|sdocx|pptx?)$/.test(x))return 'document';
 return 'file';
}
function formatSize(n){
 if(n==null||n<0)return '';
 if(n>=1024**3)return (n/(1024**3)).toFixed(n>=10*1024**3?0:1)+' GB';
 if(n>=1024**2)return (n/(1024**2)).toFixed(n>=10*1024**2?0:1)+' MB';
 return Math.round(n/1024)+' KB';
}
function label(e){
 const c=classify(e);
 return {comic:'COMIC',archive:'ARCHIVE',image:'IMAGE',video:'VIDEO',
   audio:'AUDIO',pdf:'PDF',book:'EBOOK',document:'DOCUMENT',
   model:'3D ASSET',file:'FILE',folder:'FOLDER'}[c];
}
function icon(c){return {comic:'▧',archive:'▦',image:'▧',video:'▶',audio:'♫',
 pdf:'▤',book:'▤',document:'▤',model:'◇',file:'□',folder:'▣'}[c];}
function tint(name){
 let h=42;for(const ch of name)h=(h*33+ch.charCodeAt(0))>>>0;
 return (h%260)+30;
}
function sortReadingSequence(items){
 return items.sort((a,b)=>{
   let difference=sortBy==='size'?(Number(a.size)||0)-(Number(b.size)||0):
     sortBy==='modified'?(Number(a.modified)||0)-(Number(b.modified)||0):
     sortBy==='type'?classify(a).localeCompare(classify(b)):
     naturalCollator.compare(a.name,b.name);
   return (sortDescending?-1:1)*(difference||naturalCollator.compare(a.name,b.name));
 });
}
function openBook(item){
 if(item.indexedSearch&&cloudMode){
   if(item.folder){
     stack.push(currentFolder);currentFolder=item.path;
     phrase='';search.value='';indexedToken++;
     indexedResults=[];indexedRefresh.hidden=true;
     request();return;
   }
   recordRecent(item);activeUri=item.uri;
   root.classList.add('fg-lib-hidden');visible=false;
   document.getElementById('home')?.classList.add('hidden');
   document.getElementById('work')?.classList.remove('hidden');
   // Search can return a file in any subfolder. Do not use the current
   // folder's image-sibling array when opening it.
   bridge()?.openTeraboxFile?.(item.path,item.name);
   return;
 }
 if(item.indexedSearch&&!cloudMode){
   if(item.folder){
     stack.push(currentFolder);currentFolder=item.id;
     phrase='';search.value='';indexedToken++;
     indexedResults=[];indexedRefresh.hidden=true;
     request();return;
   }
   recordRecent(item);
   activeUri=item.uri;
   root.classList.add('fg-lib-hidden');visible=false;
   document.getElementById('home')?.classList.add('hidden');
   document.getElementById('work')?.classList.remove('hidden');
   bridge()?.openIndexedFile?.(item.id);
   return;
 }
 if(item.folder){
   // A folder is a folder. Image reading is a deliberate separate action.
   enterFolder(item.id);return;
 }
 recordRecent(item);
 // Playlist follows the currently visible folder and never requests
 // a full-file transfer to navigate between movies.
 if(classify(item)==='video'){
   const candidates=sortReadingSequence(entries.filter(x=>!x.folder&&classify(x)==='video'))
     .slice(0,1500);
   bridge()?.setVideoQueue?.(JSON.stringify({entries:candidates.map(x=>({
     name:x.name,uri:x.terabox?'':x.uri,path:x.terabox?x.path:'',size:x.size
   }))}));
 }
 activeUri=item.uri;
 root.classList.add('fg-lib-hidden');
 visible=false;
 document.getElementById('home')?.classList.add('hidden');
 document.getElementById('work')?.classList.remove('hidden');
 if(cloudMode && item.terabox && classify(item)==='image' &&
     bridge()?.beginTeraboxImageCollection){
   const version=++imagePumpVersion;
   const state={version,siblings:[],known:new Set(),offset:0,started:false,
     live:scanning,folder:currentFolder,paused:false,feed:null,terabox:true,preparing:true};
   imagePumpState=state;
   bridge().beginTeraboxImageCollection(item.path,item.name);
   setTimeout(()=>{
     if(imagePumpState!==state)return;
     state.siblings=sortReadingSequence(entries.filter(x=>!x.folder&&x.terabox&&classify(x)==='image'));
     state.known=new Set(state.siblings.map(x=>x.path||x.uri));
     state.preparing=false;state.feed?.();
   },16);
 }else if(cloudMode && item.terabox){
   visible=true;root.classList.remove('fg-lib-hidden');
    bridge()?.openTeraboxFile?.(item.path,item.name,Number(item.size)||-1);
 }else if(classify(item)==='image' && bridge()?.beginImageCollection){
   // The library already has the siblings' SAF URIs. Reusing them avoids
   // a second expensive Android storage query (or a failed scan).
   const version=++imagePumpVersion;
   const state={version,siblings:[],known:new Set(),offset:0,started:false,
     live:scanning,folder:currentFolder,paused:false,feed:null,preparing:true};
   imagePumpState=state;
   bridge().beginImageCollection(item.uri);
   setTimeout(()=>{
     if(imagePumpState!==state)return;
     state.siblings=sortReadingSequence(entries.filter(x=>!x.folder&&classify(x)==='image'));
     state.known=new Set(state.siblings.map(x=>x.path||x.uri));
     state.preparing=false;state.feed?.();
   },16);
 }else if(classify(item)==='image' && bridge()?.openImageInFolder){
   bridge().openImageInFolder(currentFolder,item.uri);
 }else if(classify(item)==='file' && bridge()?.openLibraryExternally){
   // Never pretend an unknown file can be decoded by a reader.
   bridge().openLibraryExternally(item.uri);
 }else{
   bridge()?.openLibraryFile?.(item.uri);
 }
}
// Never start five remote archive transfers at once. Image requests run only
// after the corresponding tile intersects the viewport; each finishes before
// the next starts. Opening a book cancels the pending (not-yet-started) work.
const queuedCovers=new WeakSet();
const coverJobs=[];
let activeCoverJobs=0;
const MAX_CLOUD_COVERS=12;
function pumpCovers(){
 while(activeCoverJobs<MAX_CLOUD_COVERS&&coverJobs.length){
   const job=coverJobs.shift();
   if(!job.image.isConnected||!visible)continue;
   activeCoverJobs++;
   const indicator=E('span','fg-lib-cover-loading','Loading cover…');
   job.cover.appendChild(indicator);
   let done=false,timeout=0;
   const finish=()=>{
     if(done)return;done=true;clearTimeout(timeout);
     job.image.removeEventListener('load',finish);
     job.image.removeEventListener('error',finish);
     indicator.remove();
     activeCoverJobs=Math.max(0,activeCoverJobs-1);
     pumpCovers();
   };
   job.image.addEventListener('load',finish);
   job.image.addEventListener('error',finish);
   timeout=setTimeout(finish,7000);
   job.image.src=job.url+(job.url.includes('?')?'&':'?')+'source=hybrid-v2';
 }
}
const coverObserver=window.IntersectionObserver?new IntersectionObserver(changes=>{
 for(const item of changes){
   if(!item.isIntersecting)continue;
   coverObserver.unobserve(item.target);
   item.target._fgStartCover?.();
 }
},{root:body,rootMargin:'640px 0px'}):null;
function loadRemoteCover(image,url,cover){
 if(queuedCovers.has(image))return;
 queuedCovers.add(image);
 coverJobs.push({image,url,cover});
 pumpCovers();
}

function closeFileActions(){operationDialog?.remove();operationDialog=null;}
function fileSheet(title,note){
 closeFileActions();
 const overlay=E('div','fg-file-sheet-overlay');
 const sheet=E('section','fg-file-sheet');
 sheet.setAttribute('role','dialog');sheet.setAttribute('aria-modal','true');
 sheet.setAttribute('aria-label',title);
 overlay.addEventListener('click',e=>{if(e.target===overlay)closeFileActions();});
 const head=E('header','fg-file-sheet-head');
 head.append(E('strong','',title),b('×','fg-file-sheet-close',closeFileActions));
 sheet.append(head);
 if(note)sheet.append(E('p','fg-file-sheet-subtitle',note));
 overlay.append(sheet);document.body.append(overlay);operationDialog=overlay;
 return sheet;
}
function fileAction(sheet,label,description,callback,{danger=false,disabled=false}={}){
 const control=b(label,'fg-file-action'+(danger?' fg-file-danger':''),callback);
 control.disabled=disabled;
 if(description)control.append(E('small','fg-file-action-description',description));
 sheet.append(control);return control;
}
function showSortMenu(){
 const sheet=fileSheet('Sort files','Choose how to order this collection');
 const labels={name:'Name',modified:'Last modified',size:'Size',type:'File type'};
 for(const [key,label] of Object.entries(labels))
   fileAction(sheet,(sortBy===key?'✓ ':'')+label,'',()=>{
     if(sortBy===key)sortDescending=!sortDescending;
     else{sortBy=key;sortDescending=(key==='modified'||key==='size');}
     setSortTitle(labels[sortBy],sortDescending);
     pageStart=0;closeFileActions();render();
   });
}
function fileProperties(item){
 const sheet=fileSheet(item.name,'Properties of the original file');
 const dl=E('dl','fg-file-details');
 const data=[
  ['Type',item.folder?'Folder':label(item)],
  ['Size',item.folder?'—':formatSize(item.size)||'Unknown'],
  ['Modified',item.modified?new Date(item.modified).toLocaleString():'Unknown'],
  ['Provider',item.terabox||/com\.openlocal\.office\.terabox\.documents/.test(item.uri||'')?'TeraBox':'Android storage'],
  ['MIME',item.mime||'Not reported']
 ];
 for(const [key,value] of data)dl.append(E('dt','',key),E('dd','',value));
 sheet.append(dl);
 fileAction(sheet,'Close','',closeFileActions);
}

 const cloudFolderButton=b('+ New folder','fg-lib-secondary fg-lib-newfolder',()=>cloudCreateFolder());
 cloudFolderButton.id='pr-cloud-newfolder';
 cloudFolderButton.setAttribute('aria-label','Create folder in TeraBox');
 toolbar.append(cloudFolderButton);
 let cloudDestination=null;
 function cloudNameField(title,label,initial,action,source){
   const sheet=fileSheet(title,'Changes are applied directly to TeraBox.');
   const input=E('input','fg-file-name-input');
   input.value=initial||'';
   input.maxLength=240;
   input.setAttribute('aria-label',label);
   sheet.append(input);
   fileAction(sheet,'Cancel','',closeFileActions);
   fileAction(sheet,'Confirm','No existing files will be overwritten',()=>{
     const name=input.value.trim();
     if(!name||name==='.'||name==='..'||name.includes('/')||       name.includes(String.fromCharCode(92))||name.includes(String.fromCharCode(10))||       name.includes(String.fromCharCode(13)))return;
     if(action==='rename'&&name===initial)return;
     closeFileActions();
     bridge()?.cloudFileOperation?.(action,source,name);
   });
   input.focus();
 }
 function cloudCreateFolder(){
   if(!cloudMode)return;
   cloudNameField('Create TeraBox folder','Folder name','','mkdir',currentFolder||'/');
 }
 function cloudChooseDestination(item,action){
   cloudDestination={item,action,path:currentFolder||'/',page:1};
   cloudBrowseDestination(cloudDestination.path,1);
 }
 function cloudBrowseDestination(path,page){
   if(!cloudDestination)return;
   cloudDestination.path=path;cloudDestination.page=page;
   const sheet=fileSheet((cloudDestination.action==='copy'?'Copy':'Move')+' to folder',
     'Destination: '+path);
   sheet.append(E('p','fg-transfer-note','Loading TeraBox folders…'));
   bridge()?.listCloudDestinationFolders?.(path,page);
 }
 function cloudFolderChoices(data){
   const state=cloudDestination;
   if(!state||!operationDialog?.isConnected||!data||
      state.path!==data.path||state.page!==data.page)return;
   const sheet=fileSheet((state.action==='copy'?'Copy':'Move')+' to folder',
     'Destination: '+state.path);
   if(data.error){
     sheet.append(E('p','fg-file-write-note',data.error));
     fileAction(sheet,'Try again','',()=>cloudBrowseDestination(state.path,state.page));
   }else{
     const sourcePath=state.item.path||'';
     const destPath=state.path||'/';
     const invalid=destPath===sourcePath||
       destPath.startsWith(sourcePath+'/')||
       (state.action==='move'&&
        destPath===(sourcePath.slice(0,sourcePath.lastIndexOf('/'))||'/'));
     fileAction(sheet,'Select this folder',invalid?
       'This destination cannot be used':'No overwrites',()=>{
       const path=state.path;
       const source=state.item.path;
       const action=state.action;
       cloudDestination=null;
       closeFileActions();
       bridge()?.cloudFileOperation?.(action,source,path);
     },{disabled:invalid});
     if(state.path!=='/'){
       fileAction(sheet,'↑ Parent folder','',()=>{
         const parent=state.path.substring(0,state.path.lastIndexOf('/'))||'/';
         cloudBrowseDestination(parent,1);
       });
     }
     for(const child of data.folders||[]){
       fileAction(sheet,'▸ '+child.name,'Open destination folder',()=>{
         cloudBrowseDestination(child.path,1);
       });
     }
     if(data.hasMore)
       fileAction(sheet,'More folders','Next 100 TeraBox entries',()=>{
         cloudBrowseDestination(state.path,state.page+1);
       });
   }
   fileAction(sheet,'Cancel','',()=>{cloudDestination=null;closeFileActions();});
 }
function showFileActions(item){
 if(item.terabox){
   const sheet=fileSheet(item.name,(item.folder?'TeraBox folder':'TeraBox · '+formatSize(item.size)));
   fileAction(sheet,item.folder?'Browse folder':'Open file',
      'Cloud content opened directly',()=>{closeFileActions();openBook(item);});
   if(!item.folder)fileAction(sheet,'Download to…',
      'Choose a destination; original remains in your cloud account',
      ()=>{closeFileActions();bridge()?.saveTeraboxFile?.(item.path,item.name);});
   if(!item.folder&&classify(item)==='image')
      fileAction(sheet,item.previewManuallyMarked?
          'Remove affected-preview flag':'Flag affected preview',
        'Use when TeraBox substitutes a placeholder despite providing a preview URL. Only private Rounder metadata changes.',
        ()=>{closeFileActions();bridge()?.markTeraboxPreview?.(
            item.path,!item.previewManuallyMarked);});
        fileAction(sheet,'Properties','Cloud file metadata',()=>fileProperties(item));
    fileAction(sheet,'Rename','Change the cloud filename',()=>{
      cloudNameField('Rename '+item.name,'New file name',item.name,
                     'rename',item.path);
    });
    fileAction(sheet,'Copy to folder','Copy within TeraBox without downloading',()=>{
      cloudChooseDestination(item,'copy');
    });
    fileAction(sheet,'Move to folder','Move within TeraBox without downloading',()=>{
      cloudChooseDestination(item,'move');
    });
    fileAction(sheet,item.folder?'Delete folder':'Delete file',
      'Remove the selected original from TeraBox',()=>{
      const confirm=fileSheet('Delete from TeraBox?',
        'This may move the item to the provider recycle bin.');
      confirm.append(E('p','fg-file-delete-name',item.path));
      fileAction(confirm,'Cancel','',closeFileActions);
      fileAction(confirm,'Delete from TeraBox','Confirm cloud deletion',()=>{
        closeFileActions();
        bridge()?.cloudFileOperation?.('delete',item.path,'');
      },{danger:true});
    },{danger:true});
if(item.folder){
     fileAction(sheet,'Allow Control inventory (30 min)',
        'Read-only recursive metadata access to this selected TeraBox folder',
        ()=>{closeFileActions();bridge()?.allowControlInventory?.(item.path);});
     fileAction(sheet,'Revoke Control inventory access',
        'Immediately stop access to the previously shared folder',
        ()=>{closeFileActions();bridge()?.revokeControlInventory?.();});
   }
   fileAction(sheet,'Close','',closeFileActions);
   return;
 }
 const kind=classify(item);
 const write=Boolean(bridge()?.canManageLibraryFiles?.());
 const sheet=fileSheet(item.name,(item.folder?'Folder':label(item))+
   (formatSize(item.size)?' · '+formatSize(item.size):''));
 const openLabel=item.folder?'Browse folder':
    kind==='image'?'Read image collection':kind==='comic'?'Read comic archive':
    kind==='archive'?'Open archive':kind==='pdf'?'Read PDF':
    kind==='video'?'Play video fullscreen':kind==='audio'?'Play audio':
    kind==='book'?'Read ebook':kind==='model'?'Open 3D asset':
    kind==='file'?'Open with Android':
    /\.(csv|xlsx|xls)$/i.test(item.name)?'Open spreadsheet':'Open document';
 fileAction(sheet,openLabel,'Use the matching file viewer',()=>{
   closeFileActions();openBook(item);
 });
 if(item.folder)
   fileAction(sheet,'Read folder images','Read images as soon as cloud metadata becomes available',()=>{
     closeFileActions();
     pendingFolderImageRead=item.id;
     enterFolder(item.id);
   });
 if(kind==='image')
   fileAction(sheet,'View only this image','Keep this image separate from its folder',()=>{
     closeFileActions();activeUri=item.uri;
     visible=false;root.classList.add('fg-lib-hidden');
     document.getElementById('home')?.classList.add('hidden');
     document.getElementById('work')?.classList.remove('hidden');
     bridge()?.openLibraryFile?.(item.uri);
   });
 if(!item.folder){
   fileAction(sheet,'Open with another app','Choose an installed Android application',()=>{
     closeFileActions();bridge()?.openLibraryExternally?.(item.uri);
   });
   fileAction(sheet,'Share original file','Share through the Android share sheet',()=>{
     closeFileActions();bridge()?.shareLibraryFile?.(item.uri);
   });
   fileAction(sheet,'Copy to folder','Stream to local or cloud storage, keeping the original',()=>{
     closeFileActions();bridge()?.copyLibraryFile?.(item.uri);
     showTransferSheet('Copying file');
   });
   fileAction(sheet,'Move to folder','Delete the original only after the destination is verified',()=>{
     closeFileActions();bridge()?.moveLibraryFile?.(item.uri);
     showTransferSheet('Moving file');
   },{disabled:!write});
 }
 if(!item.folder){
   const lower=item.name.toLowerCase();
   const image=/\.(png|jpe?g|bmp|webp)$/.test(lower);
   const firstPdf=/\.pdf$/.test(lower);
   const separated=/\.(csv|tsv)$/.test(lower);
   if(image||firstPdf||separated)
     fileAction(sheet,'Convert format','Save as a new file; the original is unchanged',()=>{
       const choose=fileSheet('Convert '+item.name,'Select a supported output. Your original is preserved.');
       const variants=image?[
         ['PNG','png'],['JPEG · 92% quality','jpeg'],['WebP · 92% quality','webp'],
         ['PDF · single image page','pdf']
       ]:firstPdf?[['PNG · first PDF page only','png']]:
         lower.endsWith('.csv')?[['TSV · tab-separated','tsv']]:
         [['CSV · comma-separated','csv']];
       for(const [label,format] of variants)
         fileAction(choose,label,'Android will ask where to save the converted file',()=>{
           closeFileActions();bridge()?.convertLibraryFile?.(item.uri,format);
         });
     });
 }
 fileAction(sheet,'Properties','Size, type, date and storage provider',()=>fileProperties(item));
 if(!item.folder){
   fileAction(sheet,'Rename','Rename in the original storage provider',()=>{
     const edit=fileSheet('Rename file','Retain the extension unless changing the format');
     const field=E('input','fg-file-name-input');field.value=item.name;
     field.maxLength=240;field.setAttribute('aria-label','New file name');
     edit.append(field);
     fileAction(edit,'Save name','',()=>{
       const name=field.value.trim();
       if(!name||name===item.name||/[/\\]/.test(name))return;
       bridge()?.renameLibraryFile?.(item.uri,name);closeFileActions();
     });
     field.focus();
   },{disabled:!write});
   fileAction(sheet,'Delete file','This permanently removes the original file',()=>{
     const confirm=fileSheet('Delete original file?','This cannot be undone.');
     confirm.append(E('p','fg-file-delete-name',item.name));
     fileAction(confirm,'Cancel','',closeFileActions);
     fileAction(confirm,'Delete permanently','Confirm deletion of the original file',()=>{
       bridge()?.deleteLibraryFile?.(item.uri);closeFileActions();
     },{danger:true});
   },{danger:true,disabled:!write});
   if(!write)sheet.append(E('p','fg-file-write-note',
     'Rename and delete require write permission from this storage provider. Re-select the collection to request write access.'));
 }
}
function showTransferSheet(title){
 const sheet=fileSheet(title,'Choose the destination in the Android folder picker.');
 const note=E('p','fg-transfer-note','After selecting a folder, the transfer will stream without loading the whole file into memory.');
 note.setAttribute('role','status');sheet.append(note);
 const progress=E('progress','fg-transfer-progress');progress.max=100;progress.value=0;
 sheet.append(progress);
 fileAction(sheet,'Cancel transfer','Safely stop copying; the original stays intact',()=>{
   bridge()?.cancelLibraryTransfer?.();closeFileActions();
 });
}
function transferProgress(progress){
 if(!operationDialog||!progress)return;
 const bar=operationDialog.querySelector('.fg-transfer-progress');
 const note=operationDialog.querySelector('.fg-transfer-note');
 if(!bar||!note)return;
 const transferred=Number(progress.transferred)||0,total=Number(progress.total)||0;
 if(total>0){
   bar.value=Math.min(100,Math.round(transferred/total*100));
   note.textContent='Copied '+formatSize(transferred)+' of '+formatSize(total)+
     ' ('+bar.value+'%)';
 }else if(transferred>0)note.textContent='Copied '+formatSize(transferred)+'…';
}
function operationResult(result){
 if(!result)return;
 const toast=E('div','fg-file-toast',
   (result.ok?'':'Action failed: ')+(result.detail||result.operation));
 toast.setAttribute('role','status');document.body.append(toast);
 setTimeout(()=>toast.remove(),4500);
 if(result.ok&&['rename','delete','move','copy','mkdir'].includes(result.operation)){collectionCacheKey='';cloudDestination=null;request();}
 if(['move','copy','transfer'].includes(result.operation))closeFileActions();
}

function card(item,i){
 if(item.folder)return folderCard(item);
 const c=classify(item),tile=E('article','fg-lib-tile fg-lib-'+c+
   (item.folder?' fg-premium-folder':' fg-premium-media'));
 if(item.folder&&item.path)tile.dataset.folderPath=item.path;
 tile.setAttribute('role','button');tile.setAttribute('tabindex','0');
 if(cloudMode&&c==='image'&&(item.previewUnavailable||item.previewManuallyMarked)){
   tile.classList.add('fg-preview-affected');
   tile.title=item.previewUnavailable?
     'TeraBox supplied no usable preview; Rounder displays the original':
     'Preview flagged by owner; Rounder displays the original';
 }
 const cover=E('div','fg-lib-cover');
 cover.style.setProperty('--fg-hue',String(tint(item.name)));
 const fallback=E('div','fg-lib-fallback');
 fallback.append(E('span','fg-lib-fallback-glyph',icon(c)),
                 E('strong','fg-lib-fallback-title',item.title));
 cover.appendChild(fallback);
 if(item.folder&&item.path){
   const mosaic=folderPreviewCache.get(item.path);
   if(mosaic?.length){
     const rail=E('div','fg-premium-mosaic');
     for(const src of mosaic.slice(0,3)){
       const im=E('img','fg-premium-mosaic-image');
       im.alt='';im.loading='lazy';im.decoding='async';
       im.src=src;rail.append(im);
     }
     cover.append(rail);
     cover.classList.add('fg-premium-cover-ready');
   }
 }
 if(item.cover){
   const image=E('img','fg-lib-cover-image');
   image.loading='lazy';image.decoding='async';
   image.alt='Cover · '+item.title;
   image.addEventListener('load',()=>{cover.classList.add('fg-lib-has-image');});
   image.addEventListener('error',()=>{if(!item.remoteCover)image.remove();});
   cover.appendChild(image);
   if(item.remoteCover){
     // Automatic, sequential, visibility-prioritized cloud thumbnail loading.
     cover._fgStartCover=()=>loadRemoteCover(image,item.cover+
         ((item.previewUnavailable||item.previewManuallyMarked)?
           (item.cover.includes('?')?'&':'?')+'own=1':''),cover);
     if(coverObserver)coverObserver.observe(cover);
     else cover._fgStartCover();
   }else image.src=item.cover;
 }
 // Keep covers unobstructed by redundant VIDEO / EBOOK / ZIP badges.
 const meta=E('div','fg-lib-tile-meta');
 meta.append(E('strong','fg-lib-item-title',item.title),
             E('span','fg-lib-item-detail',item.indexedSearch?(item.searchPath||'Collection root'):(formatSize(item.size)||
               (item.folder?'Open collection':''))));
 tile.append(cover,meta);
 const more=b('⋯','fg-lib-more',event=>{
   event.stopPropagation();showFileActions(item);
 });
 more.setAttribute('aria-label','More actions for '+item.name);
 more.setAttribute('title','Rename, move, copy and delete');
 meta.append(more);
 if(item.folder){
   const detail=meta.querySelector('.fg-lib-item-detail');
   if(detail)detail.textContent='Folder';
 }
 // No three-dot overlays on the owner's thumbnails.
 // File actions remain available by long press and right-click.
 tile.addEventListener('contextmenu',event=>{
   event.preventDefault();showFileActions(item);
 });
 let hold=0;
 tile.addEventListener('pointerdown',e=>{
   if(e.pointerType==='touch' && !e.target.closest('.fg-lib-more')){
     hold=setTimeout(()=>{hold=0;tile.dataset.longPressed='yes';showFileActions(item);},580);
   }
 },{passive:true});
 for(const event of ['pointerup','pointercancel','pointermove'])
   tile.addEventListener(event,()=>{if(hold){clearTimeout(hold);hold=0;}},{passive:true});
 tile.addEventListener('click',e=>{
   if(e.target.closest('.fg-lib-more'))return;
   if(tile.dataset.longPressed){delete tile.dataset.longPressed;return;}
   openBook(item);
 });
 tile.addEventListener('keydown',e=>{
   if(e.target!==tile)return;
   if(e.key==='Enter'||e.key===' '){e.preventDefault();openBook(item);}
 });
 return tile;
}

function folderAccent(name){
 const lower=(name||'').toLowerCase();
 if(/art|image|gallery|illustration|pixiv|photo/.test(lower))return ['◈','art'];
 if(/book|comic|manga|novel|pdf/.test(lower))return ['▤','book'];
 if(/app|apk|program|software/.test(lower))return ['▦','app'];
 if(/video|movie|film/.test(lower))return ['▶','video'];
 if(/sound|music|audio/.test(lower))return ['♫','audio'];
 if(/3d|model|blend|koikatsu/.test(lower))return ['⬡','model'];
 return ['▰','folder'];
}
function folderCard(item){
 const [,kind]=folderAccent(item.name);
 const tile=E('article','fg-vault-folder-row fg-vault-folder-'+kind);
 const open=b('','fg-vault-folder',()=>openBook(item));
 open.setAttribute('aria-label','Open folder '+item.name);
 open.title=item.name;
 const artwork=E('span','fg-vault-folder-art');
 const ns='http://www.w3.org/2000/svg';
 const svg=document.createElementNS(ns,'svg');
 svg.setAttribute('viewBox','0 0 24 24');
 svg.setAttribute('width','24');
 svg.setAttribute('height','24');
 svg.setAttribute('fill','none');
 svg.setAttribute('stroke','currentColor');
 svg.setAttribute('stroke-width','1.8');
 svg.setAttribute('stroke-linecap','round');
 svg.setAttribute('stroke-linejoin','round');
 svg.setAttribute('aria-hidden','true');
 const shape=document.createElementNS(ns,'path');
 shape.setAttribute('d','M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V7z');
 svg.append(shape);artwork.append(svg);
 const meta=E('span','fg-vault-folder-content');
 const title=E('strong','fg-vault-folder-name',item.name);
 title.title=item.name;
 meta.append(title,E('small','','Folder · TeraBox'));
 open.append(artwork,meta);
 const actions=b('⋯','fg-vault-folder-actions',()=>showFileActions(item));
 actions.setAttribute('aria-label','Manage folder '+item.name);
 actions.title='Rename, copy, move or delete '+item.name;
 tile.append(open,actions);
 return tile;
}
function drawFolderRail(folders,mode){
 if(!folders.length){
   folderRail.hidden=true;virtualFolderKey='';
   return;
 }
 folderRail.hidden=false;
 if(!folderRegion||!folderRegion.top.isConnected){
   const title=E('h2','fg-vault-section-heading',
     mode==='search'?'Matching collections':'Collections');
   const top=E('div','fg-vault-spacer');
   const grid=E('div','fg-vault-folder-grid');
   const bottom=E('div','fg-vault-spacer');
   folderRail.replaceChildren(title,top,grid,bottom);
   folderRegion={title,top,grid,bottom};
 }
 folderRegion.title.textContent=mode==='search'?'Matching collections':'Collections';
 const columns=2;
 const rows=Math.ceil(folders.length/columns),rowHeight=140;
 const origin=folderRegion.top.getBoundingClientRect().top-
   body.getBoundingClientRect().top+body.scrollTop;
 const relative=Math.max(0,body.scrollTop-origin);
 const firstRow=Math.max(0,Math.min(rows,Math.floor(relative/rowHeight)-4));
 const visibleRows=Math.max(10,Math.ceil(body.clientHeight/rowHeight)+12);
 const endRow=Math.min(rows,firstRow+visibleRows);
 const start=firstRow*columns;
 const end=Math.min(folders.length,endRow*columns);
 folderRegion.top.style.height=(firstRow*rowHeight)+'px';
 folderRegion.bottom.style.height=((rows-endRow)*rowHeight)+'px';
 const visible=folders.slice(start,end);
 const sig=mode+'|'+start+'|'+end+'|'+folders.length+'|'+
   visible.map(x=>(x.path||x.id||x.name)+':' +
     (folderPreviewCache.get(x.path||'')||[]).length).join('\n');
 if(sig===virtualFolderKey)return;
 virtualFolderKey=sig;
 folderRegion.grid.replaceChildren(...visible.map(folderCard));
}
function resetVirtual(mode,key){
 if(mode===virtualMode&&key===virtualKey)return;
 virtualMode=mode;virtualKey=key;virtualFolderKey='';virtualDataKey='';
 virtualLastStart=-1;virtualLastEnd=-1;virtualLastSig='';
 retainedCards.clear();lastVisibleKeys='';
 deck.replaceChildren();
 virtualTop.style.height='0px';virtualBottom.style.height='0px';
 body.scrollTop=0;
}
function showVirtualItems(items,mode,key){
 resetVirtual(mode,key);
 // Folder links are compact separate navigation surfaces, not giant fake covers.
 const dataKey=mode+'|'+key+'|'+items.length+'|'+
   (mode==='browse'?catalogRevision:indexedToken);
 if(dataKey!==virtualDataKey){
   virtualDataKey=dataKey;
   virtualFolders=[];
   virtualFiles=items;
   virtualHasCovers=virtualFiles.some(e=>!!e.cover);
   virtualHasVideos=virtualFiles.some(e=>classify(e)==='video');
   virtualHasPortraits=virtualFiles.some(e=>classify(e)!=='video');
 }
 const files=virtualFiles;
 drawFolderRail([],mode);
 const useList=list;
 const hasVideos=!useList&&virtualHasVideos;
 deck.className='fg-lib-grid'+(useList?' fg-lib-list':' fg-vault-media-grid')+
   (hasVideos?' fg-lib-video-grid':'');
 const computedCols=(window.getComputedStyle(deck).gridTemplateColumns.match(/[0-9.]+px/g)||[]).length;
 const columns=useList?1:Math.max(1,computedCols||2);
 const gridWidth=Math.max(100,deck.clientWidth);
 const cellWidth=(gridWidth-15*Math.max(0,columns-1))/columns;
 const rowHeight=useList?124:Math.ceil(cellWidth*(hasVideos&&!virtualHasPortraits?9/16:1.5)+86);
 virtualCols=columns;virtualRowHeight=rowHeight;
 const totalRows=Math.ceil(files.length/columns);
 const origin=virtualTop.getBoundingClientRect().top-
   body.getBoundingClientRect().top+body.scrollTop;
 const relative=Math.max(0,body.scrollTop-origin);
 const windowRows=Math.max(10,Math.ceil(body.clientHeight/rowHeight)+12);
 // Never allow the virtual window to run beyond the final row.
 // Retain actual cards through the end of Android WebView fling gestures.
 const firstRow=Math.max(0,Math.min(Math.max(0,totalRows-windowRows),
   Math.floor(relative/rowHeight)-4));
 const endRow=Math.min(totalRows,firstRow+windowRows);
 const start=firstRow*columns;
 const end=Math.min(files.length,endRow*columns);
 virtualTop.style.height=(firstRow*rowHeight)+'px';
 virtualBottom.style.height=((totalRows-endRow)*rowHeight)+'px';
 const signature=mode+'|'+key+'|'+start+'|'+end+'|'+useList+'|'+columns+'|'+rowHeight+'|'+
   files.slice(start,end).map(x=>x.path||x.uri||x.id||x.name).join('\n');
 if(signature===virtualLastSig)return;
 const needed=files.slice(start,end).map((entry,idx)=>{
   const id=(entry.path||entry.uri||entry.id||entry.name)+'|'+list;
   let cached=retainedCards.get(id);
   if(!cached){cached=card(entry,start+idx);retainedCards.set(id,cached);}
   return cached;
 });
 needed.forEach((node,i)=>{
   const existing=deck.children[i];
   if(existing!==node)deck.insertBefore(node,existing||null);
 });
 while(deck.children.length>needed.length)deck.lastElementChild.remove();
 if(retainedCards.size>300){
   const keep=new Set(files.slice(start,end).map(x=>
     (x.path||x.uri||x.id||x.name)+'|'+list));
   for(const id of retainedCards.keys())
     if(!keep.has(id))retainedCards.delete(id);
 }
 virtualLastStart=start;virtualLastEnd=end;virtualLastSig=signature;
}
function handleContinuousScroll(){
 if(!visible||(!virtualFiles.length&&!virtualFolders.length&&!indexedMode()))return;
 if(virtualScrollTimer)return;
 virtualScrollTimer=requestAnimationFrame(()=>{
   virtualScrollTimer=0;
   if(indexedMode()){
     renderIndexedResults();
     const nearEnd=body.scrollTop+body.clientHeight+1000>=body.scrollHeight;
     if(nearEnd&&indexedMore&&!indexedPending)
       scheduleIndexSearch(indexedResults.length,0);
   }else render();
 });
}
body.addEventListener('scroll',handleContinuousScroll,{passive:true});
window.addEventListener('resize',()=>{virtualLastSig='';if(visible)render();});

function render(){
 if(indexedMode()){renderIndexedResults();return;}
 indexedRefresh.hidden=true;
 if(renderTimer){clearTimeout(renderTimer);renderTimer=0;}
 empty.replaceChildren();
 root.querySelectorAll('.fg-lib-pager').forEach(node=>node.remove());
 const needle=phrase.trim().toLocaleLowerCase();
 const key=[currentFolder,filter,needle,sortBy,sortDescending?'desc':'asc'].join('\u0001');
 let shown;
 if(indexedRevision===catalogRevision&&indexedKey===key)shown=indexedEntries;
 else{
   shown=entries.filter(e=>{
     const c=classify(e);
     return (filter==='all'||c===filter||c==='folder')&&
       (!needle||(e.title||e.name).toLocaleLowerCase().includes(needle));
   }).sort((a,b)=>{
     if(a.folder!==b.folder)return a.folder?-1:1;
     let value=0;
     if(sortBy==='size')value=(a.size||0)-(b.size||0);
     else if(sortBy==='modified')value=(a.modified||0)-(b.modified||0);
     else if(sortBy==='type')value=classify(a).localeCompare(classify(b));
     else value=naturalCollator.compare(a.name,b.name);
     return (sortDescending?-1:1)*(value||
       naturalCollator.compare(a.name,b.name));
   });
   indexedEntries=shown;indexedRevision=catalogRevision;indexedKey=key;
 }
 const viewKey=key+'|'+list;
 showVirtualItems(shown,'browse',viewKey);
 loading.classList.add('fg-lib-hidden');deck.classList.remove('fg-lib-hidden');
 empty.classList.remove('fg-lib-hidden');
 const folders=shown.filter(x=>x.folder).length;
 const files=shown.length-folders;
 count.textContent=folders+' folders · '+files+' files'+(scanning?' · syncing':'');
 if(!shown.length)empty.append(
   E('strong','',entries.length?'No matches':'This folder is empty'),
   E('p','',entries.length?'Try a different search or filter.':
     'There are no files in this TeraBox folder.'));
}
function createFilters(){
 filters.replaceChildren();
 for(const [key,name] of categories){
   if(key==='affected'&&!cloudMode)continue;
   const control=b(name,'fg-lib-filter'+(filter===key?' fg-lib-selected':''),
     ()=>{
       const wasAffected=filter==='affected';
       filter=key;pageStart=0;createFilters();
       if(indexedMode()){
         if(cloudMode&&!wasAffected&&filter==='affected')
           bridge()?.stopTeraboxListing?.();
         indexedResults=[];indexedMore=false;indexedError='';
         scheduleIndexSearch(0,0);
         render();
       }else if(cloudMode&&wasAffected&&!phrase.trim()){
         indexedToken++;
         request();
       }else render();
     });
   filters.append(control);
 }
}
function request(){
  cloudFolderButton.hidden=!cloudMode;
 if(cloudMode && !bridge()?.getTeraboxConfigured?.()){showTeraboxSettings();return;}
 if(!cloudMode && !bridge()?.getLibraryRoot?.()){showEmpty();return;}
 const nextCollectionKey=(cloudMode?'cloud:':'saf:')+(currentFolder||'/');
 const sameCollection=collectionCacheKey===nextCollectionKey;
 collectionCacheKey=nextCollectionKey;
 activeVersion=null;scanning=true;pageStart=0;
 if(!sameCollection){entries=[];entryIds.clear();}
 loaded=false;
 catalogRevision++;indexedRevision=-1;indexedEntries=[];
 // Reuse existing IMG nodes when revisiting this folder; unchanged covers
 // must not blink or be downloaded again during metadata refresh.
 if(!sameCollection){retainedCards.clear();lastVisibleKeys='';deck.replaceChildren();}
 listingRequest=currentFolder||'';lastRender=0;
 if(renderTimer){clearTimeout(renderTimer);renderTimer=0;}
 loading.textContent='Loading your collection…';
 loading.classList.toggle('fg-lib-hidden',sameCollection&&!!lastVisibleKeys);
 deck.classList.toggle('fg-lib-hidden',!lastVisibleKeys);
 empty.classList.add('fg-lib-hidden');
  if(cloudMode){
    bridge()?.startTeraboxIndex?.();
    bridge()?.listTerabox?.(currentFolder||'');
  }
 else bridge()?.listLibrary?.(currentFolder);
}
function showTeraboxSettings(){
 document.body.classList.add('fg-rounder-library-visible');
 closeFileActions();
 visible=true;root.classList.remove('fg-lib-hidden');
 document.getElementById('home')?.classList.add('hidden');
 document.getElementById('work')?.classList.add('hidden');
 welcome.classList.add('fg-lib-hidden');
 toolbar.classList.remove('fg-lib-hidden');
 filters.classList.add('fg-lib-hidden');
 deck.classList.add('fg-lib-hidden');
 loading.classList.add('fg-lib-hidden');
 empty.classList.remove('fg-lib-hidden');empty.replaceChildren();
 heading.textContent='Native TeraBox';
 const panel=E('div','fg-tb-setup');
 const head=E('h2','','Connect your TeraBox');
 const desc=E('p','','Paste your ndus session cookie. It stays encrypted on this phone. TeraBox browsing works directly on this phone.');
 const input=E('input','fg-tb-cookie');
 input.type='password';input.autocomplete='off';input.spellcheck=false;
 input.placeholder='ndus cookie';input.setAttribute('aria-label','TeraBox ndus cookie');
 const info=E('p','fg-tb-status','');
 const save=b('Connect securely','fg-lib-primary',()=>{
   if(!bridge()?.setTeraboxCookie?.(input.value.trim())){
     info.textContent='Invalid cookie format. Paste only the ndus value or ndus=…';
     return;
   }
   input.value='';cloudMode=true;currentFolder='';stack=[];
   show();
 });
 const local=b('Local library','fg-lib-secondary',()=>{
   cloudMode=false;currentFolder='';stack=[];show();
 });
 const disconnect=b('Disconnect TeraBox','fg-lib-secondary',()=>{
   bridge()?.disconnectTerabox?.();cloudMode=false;currentFolder='';stack=[];
   show();
 });
 panel.append(head,desc,input,info,save);
 if(bridge()?.getTeraboxConfigured?.())panel.append(disconnect);
 empty.append(panel);
}
const tbStyles=E('style','');
tbStyles.textContent=[
'.fg-tb-setup{display:flex;flex-direction:column;gap:14px;max-width:580px;margin:24px auto;padding:24px;border-radius:20px;background:#182235;border:1px solid #3e526d;color:#eff4ff}',
'.fg-tb-setup h2{margin:0;font:700 24px/1.2 system-ui}',
'.fg-tb-setup p{margin:0;font:14px/1.5 system-ui;color:#bcc9da}',
'.fg-tb-setup .fg-tb-cookie{width:100%;box-sizing:border-box;padding:16px;border-radius:12px;background:#0c1321;border:1px solid #677994;color:#fff;font:15px system-ui;min-height:52px}',
'.fg-lib-actions [aria-pressed=true]{border-color:#8eacff!important;background:#344c76!important}'
].join('\n');
document.head.append(tbStyles);
function showEmpty(){
 loaded=false;entries=[];entryIds.clear();deck.replaceChildren();loading.classList.add('fg-lib-hidden');
 welcome.classList.remove('fg-lib-hidden');toolbar.classList.add('fg-lib-hidden');
 filters.classList.add('fg-lib-hidden');empty.classList.remove('fg-lib-hidden');
 empty.replaceChildren();
 const add=b('Add a collection','fg-lib-large-action',
   ()=>bridge()?.pickLibraryFolder?.());
 empty.append(E('div','fg-lib-empty-icon','▧'),
   E('strong','','Build your personal library'),
   E('p','','Select a folder to see the actual covers of your books and image archives.'),
   add);
}
function enterFolder(id){
 const entry=entries.find(x=>x.folder&&x.id===id);
 if(!entry){error('Could not open folder');return;}
 // If the directory contains no images, show its regular file listing
 // instead of leaving a hidden library and an empty reader.
 root.classList.remove('fg-lib-hidden');
 visible=true;
 document.getElementById('home')?.classList.add('hidden');
 document.getElementById('work')?.classList.add('hidden');
 stack.push(currentFolder);currentFolder=id;request();
}
function openSibling(direction){
 const sorted=sortReadingSequence(entries.filter(e=>!e.folder&&
   /\.(epub|pdf|zip|cbz|cbr|jpe?g|png|webp|gif|avif)$/i.test(e.name||'')));
 const i=sorted.findIndex(e=>e.uri===activeUri);
 if(i<0||!sorted.length)return false;
 const next=sorted[i+Math.sign(direction)];
 if(!next)return false;
 openBook(next);
 return true;
}
function navigateBack(){
 pendingFolderImageRead=null;
 closeFileActions();
 if(stack.length){currentFolder=stack.pop();request();return;}
 show();
}
function hideForReader(){
 document.body.classList.remove('fg-rounder-library-visible');
 // External ACTION_VIEW images may reach window.loadDocument before the
 // library initializes. Never cover the active image with the welcome page.
 visible=false;root.classList.add('fg-lib-hidden');
 document.getElementById('home')?.classList.add('hidden');
 document.getElementById('work')?.classList.remove('hidden');
}
function show(){
 document.body.classList.add('fg-rounder-library-visible');
 bridge()?.setReadingImmersive?.(false);
 document.body.classList.remove('fg-reader-fullscreen','fg-reader-chrome-open');
 pendingFolderImageRead=null;
 closeFileActions();
 imagePumpVersion++;imagePumpState=null;
 window.FileGestionerReading?.close?.();
 document.body.classList.remove('fg-folder-reading');
 visible=true;root.classList.remove('fg-lib-hidden');
 document.getElementById('home')?.classList.add('hidden');
 document.getElementById('work')?.classList.add('hidden');
 document.body.classList.remove('fg-book-mode','fg-pdf-reading','fg-unified-reading');
 if(!cloudMode&&!bridge()?.getLibraryRoot?.()){showEmpty();drawDashboard();return;}
 welcome.classList.add('fg-lib-hidden');toolbar.classList.remove('fg-lib-hidden');
 tbBtn.setAttribute('aria-pressed',cloudMode?'true':'false');
 collectionBtn.setAttribute('aria-pressed',cloudMode?'false':'true');
  heading.textContent=cloudMode?(currentFolder?'Collection':'TeraBox'):'Your library';
  search.placeholder=cloudMode?'Search all TeraBox folders':'Search all subfolders';
 filters.classList.remove('fg-lib-hidden');
 createFilters();
 drawDashboard();
 request();
}
function append(payload){
 if(!payload||!Array.isArray(payload.items))return;
 if(cloudMode&&!currentFolder&&!phrase.trim()){
   const pending=payload.items.filter(x=>x.folder&&x.path&&!requestedFolderPreviews.has(x.path))
     .slice(0,24).map(x=>x.path);
   if(pending.length){
     pending.forEach(p=>requestedFolderPreviews.add(p));
     void 0; /* No remote folder mosaic fetch. */
   }
 }
 if(indexedMode())return;
 if('requestedFolder' in payload && payload.requestedFolder!==listingRequest)return;
 if(activeVersion===null){
   activeVersion=payload.version;
   // SAF reports the resolved tree document ID, not the empty root alias.
   currentFolder=payload.folder||currentFolder;
 }
 if(payload.version!==activeVersion||payload.folder!==currentFolder)return;
 const fresh=payload.items.filter(e=>!e.folder&&classify(e)==='image');
 for(const item of payload.items){const id=item.path||item.uri||item.id;
   if(!entryIds.has(id)){entries.push(item);entryIds.add(id);}
 }
 catalogRevision++;
 scanning=!payload.finished;loaded=true;
 const state=imagePumpState;
 if(state && state.live && state.folder===payload.folder){
   for(const entry of fresh){
     const key=entry.path||entry.uri;
     if(state.known.has(key))continue;
     state.known.add(key);state.siblings.push(entry);
   }
   if(payload.finished)state.live=false;
   if(state.paused&&state.feed){state.paused=false;state.feed();}
 }
 if(pendingFolderImageRead===payload.folder&&fresh.length){
   pendingFolderImageRead=null;
   const first=entries.filter(e=>!e.folder&&classify(e)==='image')
     .sort((a,b)=>naturalCollator.compare(a.name,b.name))[0];
   if(first)openBook(first);
 }
 if(payload.finished&&pendingFolderImageRead===payload.folder)pendingFolderImageRead=null;
 // Keep the indexed metadata for paging and image streaming, but do not
 // repeatedly rebuild the hidden library during an active reader session.
 if(!visible)return;
 loading.classList.add('fg-lib-hidden');deck.classList.remove('fg-lib-hidden');
 empty.classList.remove('fg-lib-hidden');
 heading.textContent=cloudMode?(currentFolder?currentFolder.split('/').filter(Boolean).slice(-1)[0]:'TeraBox'):'Local files';
 welcome.classList.add('fg-lib-hidden');toolbar.classList.remove('fg-lib-hidden');
 filters.classList.remove('fg-lib-hidden');
 // Do not debounce every 96-row batch: fast cloud streams would otherwise
 // postpone the first visible page until the entire huge folder is scanned.
 // During an unbounded cloud folder scan, keep the first rendered page
 // stable and make the catalog immediately navigable. A full filter+sort on
 // each incoming 100-row batch is O(totalPages × N log N) and can freeze
 // WebView for minutes at 300K images. Sorting runs only on user navigation,
 // filtering, or once when the scan completes.
 if(!payload.finished&&filter==='all'&&!phrase.trim()&&
     entries.length>PAGE&&virtualLastEnd>0){
    count.textContent=entries.length.toLocaleString()+' items · syncing…';
    if(body.scrollTop+body.clientHeight+800>=body.scrollHeight &&
       Date.now()-lastRender>=1100){
      render();lastRender=Date.now();
    }
    return;
  }
  const elapsed=Date.now()-lastRender;
 if(!lastRender||payload.finished||elapsed>=240){
   if(renderTimer){clearTimeout(renderTimer);renderTimer=0;}
   render();lastRender=Date.now();
 }else if(!renderTimer){
   renderTimer=setTimeout(()=>{renderTimer=0;render();lastRender=Date.now();},240-elapsed);
 }
}
function receive(payload){
 loaded=true;
 entries=Array.isArray(payload.items)?payload.items:[];
  entryIds.clear();for(const e of entries)entryIds.add(e.path||e.uri||e.id);
 catalogRevision++;
 currentFolder=payload.folder||currentFolder;
 loading.classList.add('fg-lib-hidden');deck.classList.remove('fg-lib-hidden');
 empty.classList.remove('fg-lib-hidden');
 heading.textContent=cloudMode?(currentFolder?currentFolder.split('/').filter(Boolean).slice(-1)[0]:'TeraBox'):'Local files';
 welcome.classList.add('fg-lib-hidden');toolbar.classList.remove('fg-lib-hidden');
 filters.classList.remove('fg-lib-hidden');render();
}
function loadingStatus(message){
 if(!visible)return;
 loading.textContent=message||'Waiting for cloud directory metadata…';
 loading.classList.toggle('fg-lib-hidden',deck.children.length>0);
 // Do not hide already loaded images for a background network status update.
 empty.classList.add('fg-lib-hidden');
}
function error(message){
 document.body.classList.add('fg-rounder-library-visible');
 // Errors during image-folder opening must be visible, not trapped behind
 // the hidden library and an empty reader.
 visible=true;root.classList.remove('fg-lib-hidden');
 loading.classList.add('fg-lib-hidden');deck.classList.add('fg-lib-hidden');
 empty.classList.remove('fg-lib-hidden');
 empty.replaceChildren(E('strong','','This folder could not be read'),
                      E('p','',message||'Check cloud availability and file permissions'),
                      b('Try again','fg-lib-large-action',request));
}
search.addEventListener('input',()=>{
 const old=indexedMode();
 phrase=search.value;pageStart=0;
 if(indexedMode()){
   if(!old){
     if(cloudMode)bridge()?.stopTeraboxListing?.();
     else bridge()?.stopLibraryListing?.();
     entries=[];entryIds.clear();retainedCards.clear();lastVisibleKeys='';
   }
   indexedResults=[];indexedMore=false;indexedError='';
   scheduleIndexSearch(0,130);
 }else{
   indexedToken++;
   if(indexedTimer)clearTimeout(indexedTimer);
   indexedResults=[];indexedPending=false;
   if(old)request();else render();
 }
 render();
});
const homeNav=b('Library','fg-lib-top-link',show);
homeNav.title='Open your library';
document.querySelector('.bar .actions')?.prepend(homeNav);
// Document opens from the Files picker and direct share intents
// must enter the same reader state as a library-card click.
const upstreamLoad=window.loadDocument;
window.loadDocument=function(payload){
 bridge()?.setReadingImmersive?.(true);
 document.body.classList.add('fg-reader-fullscreen');
 document.body.classList.remove('fg-reader-chrome-open');
 visible=false;
 root.classList.add('fg-lib-hidden');
 document.getElementById('home')?.classList.add('hidden');
 document.getElementById('work')?.classList.remove('hidden');
 return upstreamLoad(payload);
};
// The hidden header can always be reopened without covering the reading surface.
const chromeToggle=E('button','fg-reader-chrome-toggle','☰');
chromeToggle.type='button';
chromeToggle.setAttribute('aria-label','Show or hide reader controls');
chromeToggle.addEventListener('click',()=>{
  document.body.classList.toggle('fg-reader-chrome-open');
});
document.body.appendChild(chromeToggle);
function folderPreviews(data){
 if(!data||typeof data!=='object')return;
 let changed=false;
 for(const [path,images] of Object.entries(data)){
   if(!Array.isArray(images))continue;
   const valid=images.filter(v=>typeof v==='string'&&
     v.startsWith('https://fg-assets.invalid/tb-cover/')).slice(0,3);
   if(valid.length){folderPreviewCache.set(path,valid);changed=true;}
 }
 if(changed&&visible&&!phrase.trim())render();
}

// Deterministic organizer: all file moves require explicit authorization or opt-in.
function closeOrganizer(){
 if(managerRunning)return;
 managerPanel?.remove();managerPanel=null;
}
function showOrganizer(){
 if(!bridge()?.getTeraboxConfigured?.()){showTeraboxSettings();return;}
 if(managerPanel)return;
 managerData=null;managerDupeData=null;managerMessage='';
 managerSelected.clear();resetDuplicateSelection();managerView='moves';managerRunning=false;
 managerPanel=E('div','fg-org-backdrop');
 const shell=E('section','fg-org-sheet');
 shell.setAttribute('role','dialog');shell.setAttribute('aria-modal','true');
 shell.setAttribute('aria-label','Cloud organizer');
 managerPanel.append(shell);document.body.append(managerPanel);
 bridge()?.organizerReview?.();bridge()?.organizerDuplicates?.();
 renderOrganizer();
}
function managerRefreshReview(){bridge()?.organizerReview?.();bridge()?.organizerDuplicates?.();}
function managerSourceSaved(){
 managerMessage='Source folder saved. Future intake scans use the confirmed location.';
 renderOrganizer();
}
function managerReview(data){
 managerData=data;renderOrganizer();
}
function managerDuplicates(data){managerDupeData=data;initializeDuplicateSelection(data);renderOrganizer();}
function managerScan(data){
 managerMessage='Incoming scan: '+(data?.folders||0)+' folders checked, '+
  (data?.indexed||0)+' metadata entries processed.'+
  (data?.partial?' Remaining folders continue in later cycles.':'');
 renderOrganizer();
}
function managerAuto(value){
 if(managerData)managerData.auto=!!value;
 managerMessage=value?'Scheduled sorting enabled for this TeraBox account.':
  'Scheduled sorting disabled.';
 renderOrganizer();
}
function managerError(data){
 managerRunning=false;
 managerMessage='Operation unavailable: '+(data?.error||'Unknown error');
 renderOrganizer();
}
function managerProgress(event){
 if(!event)return;
 if(event.finished){
   managerRunning=false;
   managerMessage='Moved '+(event.moved||0)+' · Review '+(event.review||0)+
    (event.stopped?' · Stopped':'');
   bridge()?.organizerReview?.();bridge()?.organizerDuplicates?.();
 }else{
   if(event.status==='moved')managerSelected.delete(event.source);
   managerMessage=(event.completed+1)+'/'+event.total+
     ' · '+(event.status==='moved'?'Moved: '+event.source:'Review: '+
     (event.error||event.source));
 }
 renderOrganizer();
}
function renderOrganizer(){
 const shell=managerPanel?.querySelector('.fg-org-sheet');
 if(!shell)return;
 shell.replaceChildren();
 const header=E('div','fg-org-header');
 const headingBox=E('div','');
 headingBox.append(E('span','fg-premium-eyebrow','CLOUD MANAGEMENT'),
   E('h2','','Organizer'));
 const close=b('✕','fg-org-close',closeOrganizer);
 close.disabled=managerRunning;close.setAttribute('aria-label','Close organizer');
 header.append(headingBox,close);shell.append(header);
 shell.append(E('p','fg-org-description',
  'Deterministic file sorting and duplicate detection. No AI. No automatic deletion.'));
 const tabs=E('div','fg-org-tabs');
 for(const [key,label] of [['moves','Sort incoming'],['dupes','Duplicates']]){
   tabs.append(b(label,managerView===key?'fg-org-tab active':'fg-org-tab',()=>{
     managerView=key;renderOrganizer();
   }));
 }
 shell.append(tabs);
 const body=E('div','fg-org-content');
 if(managerView==='moves'){
   if(!managerData){
     body.append(E('p','fg-org-muted','Reading indexed file rules…'));
   }else{
     const count=managerData.proposals?.length||0;
     if(count)body.append(E('p','fg-org-stat',count+' files ready to sort'+
       (managerData.hasMore?' · more available after review':'')));
     else{
       const empty=E('div','fg-org-empty-state');
       empty.append(E('strong','','No files waiting to be sorted'),
         E('p','','Files already in their folders stay there. Check for new downloads, or optionally add an export folder from another Asterion app.'));
       body.append(empty);
     }
     const apps=E('details','fg-org-source-settings');
     apps.append(E('summary','fg-org-source-header',
       'Optional app export folders'));
     const sourceList=E('div','fg-org-source-list');
     sourceList.append(E('p','fg-org-muted',
       'Select real incoming/export folders. A file extension alone cannot identify its originating app. Historical folders can be mapped to Asterion Downloader; they are not separate apps.'));
     for(const source of managerData.sources||[]){
       const record=E('div','fg-org-source-record');
       const sourceLabel=E('label','fg-org-source-name',source.name);
       const sourceInput=E('input','fg-org-source-input');
       sourceInput.type='text';sourceInput.value=source.root||'';
       sourceInput.placeholder='/Actual/App/Export/Folder';
       sourceInput.autocomplete='off';
       sourceInput.setAttribute('aria-label',source.name+' incoming folder');
       const autoRow=E('label','fg-org-auto');
       const auto=E('input','');auto.type='checkbox';
       auto.checked=!!source.auto;
       autoRow.append(auto,E('span','','Allow scheduled moves for this source'));
       const save=b('Save source','fg-org-small',()=>{
         if(auto.checked&&!source.auto&&!window.confirm(
           'Allow automatically moving files from '+source.name+
           ' after configuring this source export folder?')){
           auto.checked=false;return;
         }
         bridge()?.organizerConfigureSource?.(source.id,sourceInput.value,auto.checked);
         managerMessage='Saving source '+source.name+'…';renderOrganizer();
       });
       record.append(sourceLabel,sourceInput,autoRow,save);sourceList.append(record);
     }
     apps.append(sourceList);body.append(apps);
     body.append(E('p','fg-org-muted',
       'Rounder never moves uncertain files. App source configuration is optional.'));
     const rows=E('div','fg-org-filelist');
     for(const item of managerData.proposals||[]){
       const row=E('label','fg-org-row');
       const input=E('input','fg-org-check');input.type='checkbox';
       input.checked=managerSelected.has(item.source);
       input.disabled=managerRunning;
       input.addEventListener('change',()=>{
         if(input.checked)managerSelected.add(item.source);
         else managerSelected.delete(item.source);
       });
       const detail=E('div','fg-org-rowtext');
       detail.append(E('strong','',item.source.split('/').pop()||item.source),
         E('span','','From '+item.source),
         E('span','fg-org-destination','To '+item.target),
         E('small','',item.reason||'Extension rule'));
       row.append(input,detail);rows.append(row);
     }
     body.append(rows);
     if(count){
     const controls=E('div','fg-org-controls');
     controls.append(b('Select first 30','fg-org-small',()=>{
       managerSelected.clear();
       for(const item of (managerData.proposals||[]).slice(0,30))
         managerSelected.add(item.source);
       renderOrganizer();
     }));
     const apply=b(managerRunning?'Moving…':'Move selected','fg-org-primary',()=>{
       const selected=[...managerSelected].slice(0,30);
       if(!selected.length)return;
       if(!window.confirm('Move '+selected.length+
           ' selected files into their proposed TeraBox destinations?'))return;
       managerRunning=true;managerMessage='Moving selected files…';
       renderOrganizer();
       bridge()?.organizerApply?.(JSON.stringify(selected));
     });
     apply.disabled=managerRunning||managerSelected.size===0;
     controls.append(apply);
     if(managerRunning)controls.append(b('Stop after current file','fg-org-small',
       ()=>bridge()?.organizerStop?.()));
     body.append(controls);
     }
   }
   const scan=b('Check incoming downloads','fg-org-scan',()=>{
     managerMessage='Checking incoming source folders…';renderOrganizer();
     bridge()?.organizerScan?.();
   });
   scan.disabled=managerRunning;body.append(scan);
   const row=E('label','fg-org-auto');
   const toggle=E('input','');toggle.type='checkbox';
   toggle.checked=!!managerData?.auto;toggle.disabled=managerRunning;
   toggle.addEventListener('change',()=>{
     if(toggle.checked&&!window.confirm(
       'Enable scheduled sorting of trusted incoming file types for this TeraBox account? File moves will happen automatically. Duplicates are never deleted.')){
       toggle.checked=false;return;
     }
     bridge()?.organizerAuto?.(toggle.checked);
   });
   row.append(toggle,E('span','','Automatically organize trusted incoming files (opt-in)'));
   body.append(row);
 }else{
   renderDuplicateWorkspace(body);
 }

 if(managerMessage)body.prepend(E('p','fg-org-status',managerMessage));
 shell.append(body);
 if(managerView==='dupes'&&managerDupeData)renderDuplicateCleanupFooter(shell);
}


/* Visual duplicate review. Only cryptographic cloud metadata groups can be
   submitted for deletion; uncertain filename/size matches remain read-only. */
const duplicateKeep=new Map();
const duplicateRemove=new Set();
let duplicateDisplayLimit=8;
function duplicateKey(g){return g.id||((g.hash||'')+':'+g.size);}
function prettySize(bytes){
 const n=Number(bytes)||0;
 if(n<1024)return n+' B';
 if(n<1048576)return (n/1024).toFixed(n<10240?1:0)+' KB';
 if(n<1073741824)return (n/1048576).toFixed(1)+' MB';
 return (n/1073741824).toFixed(2)+' GB';
}
function resetDuplicateSelection(){
 duplicateKeep.clear();duplicateRemove.clear();duplicateDisplayLimit=8;
}
function initializeDuplicateSelection(data){
 resetDuplicateSelection();
 let n=0;
 for(const g of data?.exact||[]){
   if(g.reviewOnly||!Array.isArray(g.files)||g.files.length<2)continue;
   duplicateKeep.set(duplicateKey(g),g.files[0].path);
   for(const file of g.files.slice(1)){
     if(n>=30)break;
     duplicateRemove.add(file.path);n++;
   }
 }
}
function selectedDuplicates(){
 const result={groups:[],files:0,bytes:0};
 for(const group of managerDupeData?.exact||[]){
   if(group.reviewOnly||!Array.isArray(group.files)||group.files.length<2)continue;
   const keep=duplicateKeep.get(duplicateKey(group))||group.files[0].path;
   const remove=group.files.filter(f=>f.path!==keep&&duplicateRemove.has(f.path))
     .map(f=>f.path);
   if(remove.length){
     result.groups.push({keep,remove});
     result.files+=remove.length;
     result.bytes+=remove.length*(Number(group.size)||0);
   }
 }
 return result;
}
function renderDuplicatePreview(file){
 const preview=E('div','fg-review-thumb');
 if(file.cover&&file.kind==='image'){
   const img=E('img','fg-review-image');
   img.alt='Actual cloud preview: '+file.name;
   img.decoding='async';img.loading='lazy';
   const failed=E('span','fg-review-fallback','Preview unavailable');
   failed.hidden=true;
   img.addEventListener('error',()=>{img.remove();failed.hidden=false;});
   img.src=file.cover;preview.append(img,failed);
 }else preview.append(E('span','fg-review-fallback',
   file.kind==='video'?'Video':file.kind==='audio'?'Audio':'File'));
 return preview;
}
function renderDuplicateWorkspace(body){
 if(!managerDupeData){
   body.append(E('p','fg-org-muted','Comparing cloud file metadata…'));
   return;
 }
 const exact=managerDupeData.exact||[],uncertain=managerDupeData.possible||[];
 const selection=selectedDuplicates();
 const intro=E('div','fg-review-intro');
 const sizeBox=E('div','fg-review-sizeblock');
 sizeBox.append(E('strong','fg-review-size',prettySize(selection.bytes)),
   E('span','fg-review-caption',selection.files+
     ' selected for removal · '+exact.length+' verified groups'));
 intro.append(sizeBox);
 const controls=E('div','fg-review-toolbar');
 controls.append(b('Select duplicates','fg-review-small',()=>{
   duplicateRemove.clear();let n=0;
   for(const g of exact){
     if(g.reviewOnly)continue;
     const keep=duplicateKeep.get(duplicateKey(g))||g.files?.[0]?.path;
     for(const f of g.files||[]){
       if(f.path!==keep&&n<30){duplicateRemove.add(f.path);n++;}
     }
   }
   renderOrganizer();
 }));
 controls.append(b('Deselect','fg-review-small',()=>{
   duplicateRemove.clear();renderOrganizer();
 }));
 intro.append(controls);
 intro.append(E('p','fg-review-explainer',
   'Each group contains matching TeraBox MD5 and size. Choose the original to keep and the copies to remove.'));
 body.append(intro);
 if(!exact.length){
   body.append(E('p','fg-org-empty-state',
     managerDupeData.indexReady?
       'No matching copies found in the indexed files.':'TeraBox indexing is still preparing duplicate comparisons.'));
 }
 for(const [i,g] of exact.slice(0,duplicateDisplayLimit).entries()){
   const files=g.files||[];
   const id=duplicateKey(g);
   const keep=duplicateKeep.get(id)||files[0]?.path||'';
   const group=E('section','fg-review-group');
   const top=E('div','fg-review-group-top');
   top.append(E('strong','','Group '+(i+1)+' · '+g.count+' copies'),
     E('span','',prettySize(g.size)+' each'));
   group.append(top);
   const rail=E('div','fg-review-rail');
   for(const file of files){
     const isKeep=file.path===keep;
     const isRemove=!isKeep&&duplicateRemove.has(file.path);
     const tile=E('div','fg-review-item'+
       (isKeep?' fg-review-keeper':isRemove?' fg-review-marked':''));
     const preview=renderDuplicatePreview(file);
     preview.append(E('span','fg-review-state',
       isKeep?'KEEP':isRemove?'REMOVE':'UNSELECTED'));
     tile.append(preview);
     tile.append(E('strong','fg-review-filename',file.name||file.path.split('/').pop()));
     const path=E('span','fg-review-path',file.path);
     path.title=file.path;tile.append(path);
     if(!g.reviewOnly){
       const actions=E('div','fg-review-item-controls');
       const keepBtn=b(isKeep?'Retained':'Keep','fg-review-keep-btn'+
         (isKeep?' active':''),()=>{
           const old=duplicateKeep.get(id)||files[0].path;
           duplicateKeep.set(id,file.path);
           duplicateRemove.delete(file.path);
           if(old!==file.path&&duplicateRemove.size<30)duplicateRemove.add(old);
           renderOrganizer();
         });
       const removeBtn=b(isRemove?'Remove ✓':'Remove','fg-review-remove-btn'+
         (isRemove?' active':''),()=>{
           if(file.path===duplicateKeep.get(id))return;
           if(duplicateRemove.has(file.path))duplicateRemove.delete(file.path);
           else if(duplicateRemove.size<30)duplicateRemove.add(file.path);
           else managerMessage='Up to 30 removals per cleanup. Deselect another copy first.';
           renderOrganizer();
         });
       removeBtn.disabled=isKeep||managerRunning;keepBtn.disabled=managerRunning;
       actions.append(keepBtn,removeBtn);tile.append(actions);
     }else tile.append(E('small','fg-review-path',
       'Additional copies exist. Review the full group before cleanup.'));
     rail.append(tile);
   }
   group.append(rail);body.append(group);
 }
 if(exact.length>duplicateDisplayLimit){
   body.append(b('Show '+Math.min(8,exact.length-duplicateDisplayLimit)+
     ' more groups','fg-review-more',()=>{
       duplicateDisplayLimit+=8;renderOrganizer();
     }));
 }
 if(uncertain.length){
   const maybe=E('details','fg-review-uncertain');
   maybe.append(E('summary','',uncertain.length+
     ' possible groups — matching filename and size only'));
   maybe.append(E('p','fg-org-muted',
     'These are not proven identical. They cannot be cleaned up without content verification.'));
   for(const g of uncertain.slice(0,20)){
     const row=E('div','fg-review-uncertain-group');
     row.append(E('strong','',g.count+' similar files · '+prettySize(g.size)));
     for(const f of g.files||[])row.append(E('span','fg-review-path',f.path));
     maybe.append(row);
   }
   body.append(maybe);
 }
}
function renderDuplicateCleanupFooter(shell){
 const selection=selectedDuplicates();
 const footer=E('div','fg-review-bottom');
 footer.append(E('strong','',selection.files+' files · '+prettySize(selection.bytes)));
 const action=b(managerRunning?'Processing…':'Clean up selected','fg-review-cleanup',()=>{
   const selected=selectedDuplicates();
   if(!selected.files||selected.files>30)return;
   if(!window.confirm('Delete '+selected.files+' matching copies ('+
     prettySize(selected.bytes)+') from TeraBox? A matching original stays in each group. Cloud deletion can be irreversible depending on TeraBox recovery settings.'))return;
   managerRunning=true;
   managerMessage='Verifying retained files and deleting selected copies…';
   renderOrganizer();
   bridge()?.organizerCleanupDuplicates?.(JSON.stringify({groups:selected.groups}));
 });
 action.disabled=managerRunning||selection.files<1||selection.files>30;
 footer.append(action);
 if(managerRunning)footer.append(b('Stop after current file','fg-review-small',
   ()=>bridge()?.organizerStop?.()));
 shell.append(footer);
}
function managerCleanupProgress(event){
 if(!event||event.operation!=='duplicates')return;
 if(event.finished){
   managerRunning=false;
   managerMessage=(event.deleted||0)+' deleted and verified · '+
     (event.review||0)+' require review'+(event.stopped?' · stopped':'');
   bridge()?.organizerDuplicates?.();
 }else{
   if(event.status==='deleted')duplicateRemove.delete(event.source);
   managerMessage=(Number(event.completed)+1)+'/'+event.total+' · '+
     (event.status==='deleted'?'Confirmed removed: '+event.source:
      'Review needed: '+(event.error||event.source));
 }
 renderOrganizer();
}


function updaterStatus(data){
 if(!data)return;
 updaterData=data;
 if(data.status==='available')updateBtn.classList.add('fg-vault-update-available');
 else if(data.status==='current'||data.status==='none')
   updateBtn.classList.remove('fg-vault-update-available');
 if(updaterPanel)renderUpdater();
}
function showUpdater(){
 if(updaterPanel)return;
 updaterPanel=E('div','fg-vault-updater-backdrop');
 const panel=E('section','fg-vault-updater-sheet');
 panel.setAttribute('role','dialog');panel.setAttribute('aria-modal','true');
 panel.setAttribute('aria-label','Update Asterion Rounder');
 updaterPanel.append(panel);
 updaterPanel.addEventListener('click',e=>{
   if(e.target===updaterPanel){updaterPanel.remove();updaterPanel=null;}
 });
 document.body.append(updaterPanel);
 renderUpdater();
 if(!updateChecked){
   updateChecked=true;bridge()?.checkRounderUpdate?.();
 }
}
function renderUpdater(){
 const panel=updaterPanel?.firstElementChild;
 if(!panel)return;
 panel.replaceChildren();
 const top=E('div','fg-vault-updater-head');
 top.append(E('strong','','Rounder Updates'),
   b('✕','fg-vault-updater-close',()=>{
     updaterPanel.remove();updaterPanel=null;
   }));
 panel.append(top,E('p','fg-vault-updater-desc',
   'Signed official releases · installed through Android'));
 if(updaterData){
   const status=E('div','fg-vault-updater-status');
   status.append(E('strong','',updaterData.message||'Update status unavailable'));
   if(updaterData.percent>=0&&updaterData.status==='downloading'){
     const meter=E('progress','');meter.max=100;meter.value=updaterData.percent;
     status.append(meter,E('small','',updaterData.percent+'% downloaded'));
   }
   if(updaterData.tag)status.append(E('small','',updaterData.tag));
   panel.append(status);
 }
 const buttons=E('div','fg-vault-updater-buttons');
 buttons.append(b('Check releases','fg-vault-updater-secondary',()=>{
   updaterData=null;renderUpdater();bridge()?.checkRounderUpdate?.();
 }));
 const install=b('Download & install','fg-vault-updater-primary',()=>{
   bridge()?.installRounderUpdate?.();
 });
 install.disabled=updaterData?.status==='downloading';
 buttons.append(install);panel.append(buttons);
 panel.append(E('p','fg-vault-updater-note',
   'Rounder verifies the APK checksum, Android package identity, signing certificate and newer version before launching the system installer. Installation requires your Android confirmation.'));
}

function previewMarkResult(data){
 if(!data||data.scope!==bridge()?.getTeraboxSearchScope?.())return;
 const ok=!!data.ok;
 if(ok){
   for(const item of entries.concat(indexedResults||[]))
     if(item.path===data.path)item.previewManuallyMarked=!!data.marked;
   catalogRevision++;indexedRevision=-1;virtualLastSig='';
   retainedCards.clear();lastVisibleKeys='';
   if(indexedMode()){
     indexedResults=[];scheduleIndexSearch(0,0);
   }else render();
 }
 const toast=E('div','fg-file-toast',ok?
    (data.marked?'Affected preview flagged':'Preview flag removed'):
    ('Could not save preview flag: '+(data.error||'unknown error')));
 toast.setAttribute('role','status');
 document.body.append(toast);
 setTimeout(()=>toast.remove(),4500);
}
function handleAndroidBack(){
 if(updaterPanel){updaterPanel.remove();updaterPanel=null;return true;}
 if(managerPanel){closeOrganizer();return true;}
 if(operationDialog){closeFileActions();return true;}
 if(window.FileGestionerReading?.handleBack?.())return true;
 // Reading settings are a modal surface, not an exit command.
 const drawer=document.querySelector('.fg-book-drawer:not(.fg-book-hidden)');
 if(drawer){drawer.classList.add('fg-book-hidden');return true;}
 if(visible){
   // Also tolerate older reader states in which the library flag remained
   // true after a document was opened from an external Android provider.
   if(!document.getElementById('work')?.classList.contains('hidden')){
     show();bridge()?.readerDiagnostic?.('BACK_TO_LIBRARY');return true;
   }
   if(stack.length){navigateBack();return true;}
   // Root collection is the app's top-level destination.
   return false;
 }
 // Return to the actual library, regardless of whether the source was
 // opened through Android Files or a collection tile.
 show();
 bridge()?.readerDiagnostic?.('BACK_TO_LIBRARY');
 return true;
}
window.FileGestionerLibrary={
  cloudFolderChoices,
 previewMarkResult,
 updaterStatus,
 folderPreviews,managerReview,managerDuplicates,managerProgress,managerScan,
 managerAuto,managerError,managerRefreshReview,managerSourceSaved,managerCleanupProgress,
 show,hideForReader,receive,append,error,loadingStatus,operationResult,transferProgress,handleBack:handleAndroidBack,enterFolder,openSibling,
 imageReaderReady:()=>{
   const state=imagePumpState;
   if(!state||state.started||state.version!==imagePumpVersion)return;
   state.started=true;
   const feed=()=>{
     if(imagePumpState!==state||imagePumpVersion!==state.version)return;
     if(state.preparing||!state.demand)return;
     if(state.offset>=state.siblings.length){
       if(state.live && scanning){state.paused=true;return;}
       state.demand=false;
       bridge()?.finishImageCollection?.();imagePumpState=null;return;
     }
     state.demand=false;
     const batch=state.siblings.slice(state.offset,state.offset+96)
       .map(x=>state.terabox?{path:x.path,name:x.name}:{uri:x.uri,name:x.name});
     state.offset+=96;
     if(state.terabox)bridge()?.appendTeraboxImageCollectionChunk?.(JSON.stringify(batch));
     else bridge()?.appendImageCollectionChunk?.(JSON.stringify(batch));
     // Next chunk is requested only when the reader nears its visible
     // end. Never pre-register all 300,000 image URLs at once.
   };
   state.feed=feed;
   state.demand=true;
   feed();
 },
 imageReaderDemand:()=>{
   const state=imagePumpState;
   if(!state||!state.started||state.version!==imagePumpVersion)return;
   state.demand=true;state.feed?.();
 },
 isVisible:()=>visible,
  receiveIndexedSearch,indexProgress,
  openCollection:()=>{
    currentFolder='';stack=[];phrase='';search.value='';
    indexedToken++;indexedResults=[];indexedRefresh.hidden=true;show();
 },missingCollection:showEmpty
};
const initializeLibrary=()=>{
 // Handle both early page loads and scripts loaded after DOMContentLoaded.
 if(window.__fgLibraryInitialized)return;
 window.__fgLibraryInitialized=true;
 if(document.body.classList.contains('fg-folder-reading'))hideForReader();
 else show();
 bridge()?.readerDiagnostic?.('LIBRARY_READY');
};
if(document.readyState==='loading')
 document.addEventListener('DOMContentLoaded',initializeLibrary,{once:true});
else initializeLibrary();
})();
