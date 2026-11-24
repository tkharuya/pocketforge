// Browser-only UI contract tests. Not an Android or live OAuth test.
const {chromium}=require('playwright');
const fs=require('fs'),path=require('path'),assert=require('assert');
(async()=>{
 const browser=await chromium.launch({executablePath:process.env.POCKET_CHROME||'/workspace/scratch/4cfcdda44742/tools/chrome-shell/chrome-headless-shell-linux64/chrome-headless-shell',headless:true,args:['--no-sandbox']});
 const page=await browser.newPage({viewport:{width:412,height:891},deviceScaleFactor:1});
 const errors=[];page.on('pageerror',e=>errors.push(e.message));
 await page.addInitScript(()=>{
  const project={id:'99d9c02d-15c0-42b6-a709-295a70269e05',name:'はじめてのプロジェクト',entryPoint:'index.html',updatedAt:Date.now()};
  const files={'index.html':'<!doctype html><h1>Test</h1>'};
  window.qa={requests:[],files,profile:null,conversation:[],models:[{slug:'test-model',display_name:'Test model',visibility:'list'}]};
  const list=()=>Object.entries(files).map(([path,content])=>({path,size:content.length,directory:false}));
  window.Native={request(raw){const r=JSON.parse(raw);qa.requests.push(r);let data;
   switch(r.action){
    case 'bootstrap':data={projects:[project],activeProject:project,profile:qa.profile,profiles:[],models:qa.profile?qa.models:[],settings:{model:qa.profile?'test-model':'',reasoning:'auto'},conversation:qa.conversation,files:list(),drive:{connected:false,setupRequired:true}};break;
    case 'listFiles':data=list();break;
    case 'readFile':data={path:r.path,content:files[r.path]};break;
    case 'writeFile':files[r.path]=r.content;data=true;break;
    case 'models':data=qa.models;break;
    case 'driveStatus':data={connected:false,setupRequired:true};break;
    case 'settings':data=r.values;break;
    case 'send':data={started:true};break;
    case 'runJavaScript':data={ok:false,error:'SyntaxError: invalid token',logs:[]};break;
    case 'history':data=[{id:'snapshot1',label:'手動編集前',createdAt:Date.now()}];break;
    case 'preview':data=true;break;
    default:data=true;
   }
   setTimeout(()=>window.receiveNative({id:r.id,ok:true,data}),5);
  }};
 });
 await page.goto('file://'+path.resolve(__dirname,'../app/src/main/assets/index.html'));
 await page.waitForFunction(()=>document.getElementById('projectName').textContent==='はじめてのプロジェクト');
 assert.equal(await page.locator('#connectionLabel').textContent(),'未接続');
 assert.equal(await page.locator('#browserBanner').isVisible(),false);
 fs.mkdirSync(path.resolve(__dirname,'../build/qa'),{recursive:true});
 await page.screenshot({path:path.resolve(__dirname,'../build/qa/01-work.png')});
 await page.locator('[data-tab="files"]').click();await page.locator('.file-row').first().click();
 await page.locator('#fileEditor').fill('<!doctype html><h1>保存テスト</h1>');
 await page.evaluate(()=>nativeEvent({type:'files',files:[{path:'index.html',size:50,directory:false}]}));
 assert((await page.locator('#fileEditor').inputValue()).includes('保存テスト'));
 await page.locator('#saveFile').click();await page.waitForFunction(()=>qa.files['index.html'].includes('保存テスト'));
 await page.locator('#closeEditor').click();await page.locator('[data-tab="run"]').click();
 await page.locator('#previewButton').click();
 assert(await page.evaluate(()=>qa.requests.some(r=>r.action==='preview'&&r.path==='index.html')));
 await page.locator('#view-run details summary').click();await page.locator('#runJsButton').click();
 await page.waitForFunction(()=>document.getElementById('jsOutput').textContent.includes('SyntaxError'));
 await page.screenshot({path:path.resolve(__dirname,'../build/qa/02-run.png')});
 await page.locator('[data-tab="settings"]').click();
 assert.equal(await page.locator('#driveSyncButton').isEnabled(),false);
 assert.equal(await page.locator('#driveClientId').count(),0);
 await page.screenshot({path:path.resolve(__dirname,'../build/qa/03-settings.png')});
 await page.evaluate(()=>{qa.profile={email:'test@example.invalid',signed_in:true,plan_enabled:false,client_id:'test'};nativeEvent({type:'auth',profile:qa.profile,profiles:[qa.profile]});});
 assert.equal(await page.locator('#planConsentButton').isVisible(),true);
 await page.evaluate(()=>{qa.profile.plan_enabled=true;nativeEvent({type:'auth',profile:qa.profile,profiles:[qa.profile]});nativeEvent({type:'models',models:qa.models,settings:{model:'test-model'}});});
 await page.locator('[data-tab="work"]').click();await page.locator('#messageInput').fill('タイマーを作って');await page.locator('#sendButton').click();
 await page.waitForFunction(()=>qa.requests.some(r=>r.action==='send'));
 const sent=await page.evaluate(()=>qa.requests.find(r=>r.action==='send'));assert.equal(sent.model,'test-model');assert.notEqual(sent.reasoning,'default');
 await page.evaluate(()=>{nativeEvent({type:'chatDelta',text:'作成しました。'});nativeEvent({type:'tool',name:'write_file',status:'running',detail:'index.html'});qa.conversation=[{role:'user',content:'タイマーを作って'},{type:'message',role:'assistant',content:[{type:'output_text',text:'作成しました。'}]}];nativeEvent({type:'done',text:'作成しました。',conversation:qa.conversation});});
 assert((await page.locator('#messages').textContent()).includes('作成しました。'));
 await page.locator('#messageInput').fill('次の指示の下書き');
 await page.evaluate(()=>nativeEvent({type:'files',files:[{path:'index.html',size:50,directory:false}]}));
 assert.equal(await page.locator('#messageInput').inputValue(),'次の指示の下書き');
 for(const width of [360,412,840]){await page.setViewportSize({width,height:820});assert(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1),'horizontal overflow '+width);}
 assert.deepEqual(errors,[]);
 console.log('UI contract QA passed: bootstrap, auth/plan, models, files/editor drafts, preview, JS error, Drive state, streaming, 3 viewport widths.');
 await browser.close();
})().catch(e=>{console.error(e);process.exit(1)});
