const express = require('express');
const crypto = require('crypto');
const {OAuth2Client} = require('google-auth-library');
const {google} = require('googleapis');
const app = express();
app.use(express.json({limit:'256kb'}));

const googleClientId = process.env.MYRA_GOOGLE_WEB_CLIENT_ID || '';
const adminEmail = (process.env.MYRA_ADMIN_EMAIL || '').trim().toLowerCase();
const rootFolderId = (process.env.MYRA_DRIVE_ROOT_FOLDER_ID || '').trim();
const sessionSecret = process.env.MYRA_SESSION_SECRET || '';
const oauthClient = new OAuth2Client(googleClientId);
let drive;

async function getDrive() {
  if (drive) return drive;
  if (!rootFolderId) throw new Error('MYRA_DRIVE_ROOT_FOLDER_ID is not configured');
  const auth = new google.auth.GoogleAuth({scopes:['https://www.googleapis.com/auth/drive']});
  drive = google.drive({version:'v3', auth});
  return drive;
}
function safeName(s){ return String(s).replace(/[^a-zA-Z0-9._-]/g,'_').slice(0,100); }
async function findFile(name, parent=rootFolderId) {
  const d=await getDrive();
  const q=`name='${String(name).replace(/'/g,"\\'")}' and '${parent}' in parents and trashed=false`;
  const r=await d.files.list({q,fields:'files(id,name,mimeType,modifiedTime)',pageSize:10});
  return r.data.files?.[0] || null;
}
async function readJsonFile(name, parent=rootFolderId, fallback) {
  const f=await findFile(name,parent); if(!f) return fallback;
  const d=await getDrive();
  const r=await d.files.get({fileId:f.id,alt:'media'}, {responseType:'text'});
  try{return JSON.parse(r.data)}catch{return fallback;}
}
async function writeJsonFile(name, data, parent=rootFolderId) {
  const d=await getDrive();
  const body=JSON.stringify(data,null,2);
  const f=await findFile(name,parent);
  if(f){ await d.files.update({fileId:f.id,media:{mimeType:'application/json',body:require('stream').Readable.from([body])}}); return f.id; }
  const r=await d.files.create({requestBody:{name,parents:[parent],mimeType:'application/json'},media:{mimeType:'application/json',body:require('stream').Readable.from([body])},fields:'id'});
  return r.data.id;
}
async function ensureFolder(name,parent=rootFolderId){
  const f=await findFile(name,parent); if(f) return f.id;
  const d=await getDrive();
  const r=await d.files.create({requestBody:{name,parents:[parent],mimeType:'application/vnd.google-apps.folder'},fields:'id'});
  return r.data.id;
}
function signSession(user){
  if(!sessionSecret) throw new Error('MYRA_SESSION_SECRET is not configured');
  const payload=Buffer.from(JSON.stringify({uid:user.userId,email:user.email,exp:Date.now()+1000*60*60*24*30})).toString('base64url');
  const sig=crypto.createHmac('sha256',sessionSecret).update(payload).digest('base64url');
  return `${payload}.${sig}`;
}
function verifySession(token){
  if(!sessionSecret) return null;
  const [payload,sig]=String(token||'').split('.'); if(!payload||!sig) return null;
  const expected=crypto.createHmac('sha256',sessionSecret).update(payload).digest('base64url');
  if(sig.length!==expected.length || !crypto.timingSafeEqual(Buffer.from(sig),Buffer.from(expected))) return null;
  try{const p=JSON.parse(Buffer.from(payload,'base64url').toString()); return p.exp>Date.now()?p:null;}catch{return null;}
}
async function persistUser(user){
  const usersFolder=await ensureFolder('users');
  const name=`${safeName(user.userId)}.json`;
  const old=await readJsonFile(name,usersFolder,{});
  await writeJsonFile(name,{...old,...user,updatedAt:Date.now()},usersFolder);
}
async function googleAuth(req,res){
  if(!googleClientId) return res.status(503).json({error:'Google auth is not configured on the server'});
  try{
    const idToken=String(req.body.id_token||''); if(!idToken) return res.status(400).json({error:'Missing Google ID token'});
    const ticket=await oauthClient.verifyIdToken({idToken,audience:googleClientId});
    const payload=ticket.getPayload();
    if(!payload||!payload.sub||!payload.email) return res.status(401).json({error:'Invalid Google identity'});
    if(payload.email_verified===false) return res.status(401).json({error:'Google email is not verified'});
    const user={userId:`google_${payload.sub}`,email:String(payload.email).toLowerCase(),displayName:String(payload.name||payload.email),isAdmin:!!(adminEmail&&String(payload.email).toLowerCase()===adminEmail)};
    await persistUser(user);
    res.json({session_token:signSession(user),...user});
  }catch(e){console.error('Google auth:',e.message);res.status(401).json({error:'Google token verification failed'});}
}
async function auth(req,res,next){
  const token=(req.headers.authorization||'').replace(/^Bearer\s+/i,'').trim();
  const session=verifySession(token); if(!session) return res.status(401).json({error:'unauthorized'});
  req.session=session; req.userId=session.uid; next();
}

app.post('/v1/auth/google',googleAuth);
app.post('/v1/auth/logout',auth,(_,res)=>res.json({ok:true}));
app.get('/v1/auth/me',auth,async(req,res)=>{try{const users=await ensureFolder('users');const u=await readJsonFile(`${safeName(req.userId)}.json`,users,{});res.json(u.userId?u:{userId:req.userId,email:req.session.email,displayName:req.session.email});}catch(e){res.status(503).json({error:'profile storage unavailable'});}});
app.get('/v1/profile',auth,async(req,res)=>{try{const users=await ensureFolder('users');const u=await readJsonFile(`${safeName(req.userId)}.json`,users,{});res.json(u);}catch(e){res.status(503).json({error:'profile storage unavailable'});}});

app.post('/v1/knowledge/publish',auth,async(req,res)=>{
  try{
    const items=Array.isArray(req.body.items)?req.body.items:[]; let accepted=0;
    const file='shared_learning.json'; const current=await readJsonFile(file,rootFolderId,[]); const seen=new Set(current.map(x=>(x.topic+'|'+x.answer).toLowerCase()));
    for(const x of items){const topic=String(x.topic||'').trim().slice(0,300),answer=String(x.answer||'').trim().slice(0,4000);if(!topic||!answer)continue;const key=(topic+'|'+answer).toLowerCase();if(seen.has(key))continue;seen.add(key);current.push({provider:String(x.provider||'Cloud').slice(0,40),topic,answer,timestamp:Number(x.timestamp)||Date.now(),approvedBy:req.userId});accepted++;}
    await writeJsonFile(file,current.slice(-1000)); res.json({accepted});
  }catch(e){res.status(503).json({error:'cloud storage unavailable'});}
});
app.get('/v1/knowledge/sync',auth,async(req,res)=>{try{const limit=Math.min(Math.max(Number(req.query.limit)||50,1),100);const items=await readJsonFile('shared_learning.json',rootFolderId,[]);res.json({items:items.slice(-limit)});}catch(e){res.status(503).json({error:'cloud storage unavailable'});}});
app.get('/health',async(_,res)=>{const configured=!!(googleClientId&&rootFolderId&&sessionSecret);if(!configured)return res.status(503).json({ok:false,googleAuth:!!googleClientId,drive:!!rootFolderId,session:!!sessionSecret});try{await getDrive();res.json({ok:true,googleAuth:true,drive:true,session:true});}catch(e){res.status(503).json({ok:false,googleAuth:true,drive:false,session:true,error:'Drive authentication unavailable'});}});

app.listen(process.env.PORT||8080,()=>console.log('MYRA Cloud Auth + Google Drive service ready'));
