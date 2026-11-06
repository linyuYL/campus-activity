<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
type Row = Record<string, any>
const token=ref(sessionStorage.getItem('campus-token')||'')
const accounts=ref<Row[]>([]), me=ref<Row|null>(null), categories=ref<Row[]>([])
const selectedAccount=ref('1'), tab=ref('activities'), busy=ref(false), notice=ref(''), failed=ref(false)
const rows=ref<Row[]>([]), detail=ref<Row|null>(null), roster=ref<Row[]>([]), reviewActivity=ref<Row|null>(null)
const pageNumber=ref(1), total=ref(0), rosterPage=ref(1), rosterTotal=ref(0)
const query=ref(''), category=ref(''), sort=ref('START_ASC'), clubId=ref('1'), targetAccount=ref('3')
const search=ref(''), searchField=ref('NAME'), role=ref('CLUB_MANAGER'), scope=ref('1')
const editing=ref<Row|null>(null), history=ref<Row[]>([])
const nowInput=(minutes:number)=>{const date=new Date(Date.now()+minutes*60000); return new Date(date.getTime()-date.getTimezoneOffset()*60000).toISOString().slice(0,16)}
const emptyForm=()=>({title:'',category:'LECTURE',location:'',description:'',capacity:30,registrationClosesAt:nowInput(5),startsAt:nowInput(10),endsAt:nowInput(70)})
const form=ref(emptyForm())
const roleNames:Row={STUDENT:'学生',CLUB_MANAGER:'社团负责人',REVIEWER:'审核老师',SYSTEM_ADMIN:'系统管理员'}
const states:Row={DRAFT:'草稿',PENDING_REVIEW:'待审核',PUBLISHED:'已发布',RETURNED:'已退回',CANCELLED:'已取消',REGISTERED:'已报名',CHECKED_IN:'已签到',ACTIVITY_CANCELLED:'活动已取消',PENDING:'待处理',APPROVED:'已通过',ACTIVE:'有效',REVOKED:'已撤销',UNAVAILABLE:'不可签到'}
const eligibility:Row={OPEN:'立即报名',FULL:'报名已满',CLOSED:'报名已截止',ALREADY_REGISTERED:'已报名',ACTIVITY_CANCELLED:'活动已取消'}
const tabs=computed(()=>[
 {id:'activities',name:'发现活动'}, {id:'mine',name:'我的报名'},
 ...(me.value?.roles.includes('CLUB_MANAGER')?[{id:'manage',name:'社团活动'}]:[]),
 ...(me.value?.roles.includes('REVIEWER')?[{id:'review',name:'活动审核'}]:[]),
 ...(me.value?.roles.includes('SYSTEM_ADMIN')?[{id:'roles',name:'角色权限'}]:[])
])
function time(value:unknown){return value?new Date(String(value)).toLocaleString('zh-CN',{hour12:false}):'—'}
async function api(path:string,method='GET',body?:unknown,headers:Record<string,string>={}) {
 const response=await fetch('/api/v1'+path,{method,headers:{'Content-Type':'application/json',...(token.value?{Authorization:'Bearer '+token.value}:{}),...(method!=='GET'&&!path.includes('searches')?{'Idempotency-Key':crypto.randomUUID()}:{}),...headers},...(body===undefined?{}:{body:JSON.stringify(body)})})
 if(response.status===204)return null
 const data=await response.json()
 if(!response.ok){if(response.status===401){token.value='';me.value=null;sessionStorage.removeItem('campus-token')}throw new Error(data.message||'请求失败')}
 return data
}
async function run(action:()=>Promise<void>,message='') {
 if(busy.value)return
 busy.value=true;notice.value='';failed.value=false
 try{await action();if(message)notice.value=message}catch(error){failed.value=true;notice.value=error instanceof Error?error.message:'请求失败'}finally{busy.value=false}
}
async function login(){await run(async()=>{
 const data=await api('/demo/sessions','POST',{accountId:selectedAccount.value});token.value=data.accessToken;sessionStorage.setItem('campus-token',token.value)
 me.value=await api('/me');categories.value=await api('/activity-categories');tab.value='activities';await load()
})}
function logout(){token.value='';me.value=null;sessionStorage.removeItem('campus-token');rows.value=[];detail.value=null}
async function load(){
 detail.value=null;reviewActivity.value=null;roster.value=[];history.value=[];editing.value=null
 let path='/activities?'+new URLSearchParams({q:query.value,sort:sort.value,...(category.value?{category:category.value}:{})})
 if(tab.value==='mine')path='/me/registrations'
 if(tab.value==='manage')path='/management/clubs/'+clubId.value+'/activities'
 if(tab.value==='review')path='/management/activity-submissions'
 if(tab.value==='roles')path='/management/accounts/'+targetAccount.value+'/role-grants'
 const data=await api(path+(path.includes('?')?'&':'?')+'page='+pageNumber.value);rows.value=data.items;total.value=data.total
}
async function switchTab(value:string){await run(async()=>{tab.value=value;pageNumber.value=1;await load()})}
async function openActivity(id:string){await run(async()=>{detail.value=await api('/activities/'+id)})}
async function signup(){
 if(!detail.value||!confirm('确认以当前账号报名该活动？'))return
 await run(async()=>{await api('/activities/'+detail.value!.id+'/registrations','POST');detail.value=await api('/activities/'+detail.value!.id)},'报名成功，可在我的报名中查看')
}
async function cancelRegistration(row:Row){if(!confirm('确认取消报名并释放名额？'))return;await run(async()=>{await api('/me/registrations/'+row.id+'/cancellation','PUT',{confirmed:true});await load()},'报名已取消')}
async function checkinView(row:Row){await run(async()=>{const view=await api('/me/registrations/'+row.id+'/check-in');notice.value='签到窗口 '+time(view.opensAt)+' 至 '+time(view.closesAt)+'，'+(states[view.status]||view.status)+'。签到时间 '+time(view.checkedInAt)})}
async function save(){await run(async()=>{
 const payload={...form.value,registrationClosesAt:new Date(form.value.registrationClosesAt).toISOString(),startsAt:new Date(form.value.startsAt).toISOString(),endsAt:new Date(form.value.endsAt).toISOString()}
 if(editing.value)await api('/management/activities/'+editing.value.id,'PATCH',payload,{'If-Match':'"'+editing.value.version+'"'})
 else await api('/management/clubs/'+clubId.value+'/activities','POST',payload)
 form.value=emptyForm();await load()
},'草稿已保存')}
function edit(row:Row){editing.value=row;form.value={title:row.title,category:row.category,location:row.location,description:row.description,capacity:row.capacity,registrationClosesAt:localInput(row.registrationClosesAt),startsAt:localInput(row.startsAt),endsAt:localInput(row.endsAt)}}
function localInput(value:string){const date=new Date(value);return new Date(date.getTime()-date.getTimezoneOffset()*60000).toISOString().slice(0,16)}
async function submit(row:Row){await run(async()=>{await api('/management/activities/'+row.id+'/submissions','POST');await load()},'已提交审核')}
async function cancelActivity(row:Row){if(!confirm('确认取消活动？已报名与签到记录将保留。'))return;await run(async()=>{await api('/management/activities/'+row.id+'/cancellation','PUT',{confirmed:true});await load()},'活动已取消')}
async function viewHistory(row:Row){await run(async()=>{const data=await api('/management/activities/'+row.id+'/submissions');history.value=data.items})}
async function viewRoster(row:Row){await run(async()=>{detail.value=row;rosterPage.value=1;await loadRoster()})}
async function loadRoster(){const data=search.value?await api('/management/activities/'+detail.value!.id+'/registration-searches','POST',{field:searchField.value,keyword:search.value,page:rosterPage.value}):await api('/management/activities/'+detail.value!.id+'/registrations?page='+rosterPage.value);roster.value=data.items;rosterTotal.value=data.total}
async function checkin(row:Row){await run(async()=>{await api('/management/activities/'+detail.value!.id+'/registrations/'+row.registrationId+'/check-in','PUT');await loadRoster()},'到场已登记')}
async function inspect(row:Row){await run(async()=>{reviewActivity.value=await api('/management/activities/'+row.activityId)})}
async function review(row:Row,decision:string){const comment=decision==='RETURNED'?prompt('请输入退回原因'):null;if(decision==='RETURNED'&&!comment)return;await run(async()=>{await api('/management/activities/'+row.activityId+'/submissions/'+row.id+'/review','PUT',{decision,...(comment?{comment}:{})});await load()},'审核结果已保存')}
async function grant(){await run(async()=>{await api('/management/accounts/'+targetAccount.value+'/role-grants','POST',{role:role.value,...(role.value==='SYSTEM_ADMIN'?{}:{scopeClubId:scope.value})});await load()},'角色已授权')}
async function revoke(row:Row){if(!confirm('确认撤销该项授权？'))return;await run(async()=>{await api('/management/accounts/'+targetAccount.value+'/role-grants/'+row.id,'DELETE');await load()},'授权已撤销')}
onMounted(async()=>{await run(async()=>{try{accounts.value=await api('/demo/accounts')}catch{notice.value='请使用已接入的认证服务获取令牌后访问'}if(token.value){me.value=await api('/me');categories.value=await api('/activity-categories');await load()}})})
</script>

<template>
 <header><div><span class="eyebrow">CAMPUS LIFE</span><h1>校园活动管理平台</h1></div><div v-if="me" class="identity"><b>{{ me.displayName }}</b><small>{{ me.roles.map((r:string)=>roleNames[r]).join(' / ') }}</small><button @click="logout">退出</button></div></header>
 <main>
  <section v-if="!me" class="login card"><h2>开始校园活动之旅</h2><p>使用课程演示账号体验报名、发布、审核与签到流程。</p><label>演示账号<select v-model="selectedAccount"><option v-for="a in accounts" :key="a.accountId" :value="a.accountId">{{ a.displayName }}</option></select></label><button class="primary" :disabled="busy||!accounts.length" @click="login">进入平台</button><p class="muted">完整演示学号为 DEMO0001、DEMO0002。</p></section>
  <template v-else>
   <nav><button v-for="t in tabs" :key="t.id" :class="{active:tab===t.id}" :disabled="busy" @click="switchTab(t.id)">{{t.name}}</button></nav>
   <div v-if="busy" class="muted" role="status">正在处理…</div>
   <div v-if="notice" class="notice" :class="{error:failed}" role="status">{{ notice }}</div>
   <section v-if="tab==='activities'">
    <div class="section-head"><div><h2>发现活动</h2><p>查看活动安排，找到你的下一次相遇。</p></div></div>
    <form class="toolbar" @submit.prevent="run(load)"><input v-model="query" placeholder="搜索活动标题" maxlength="100"><select v-model="category"><option value="">全部分类</option><option v-for="c in categories" :value="c.code">{{c.name}}</option></select><select v-model="sort"><option value="START_ASC">按开始时间</option><option value="PUBLISHED_DESC">最新发布</option></select><button :disabled="busy">查询</button></form>
    <p v-if="!rows.length" class="empty">暂无活动</p>
    <div class="grid"><article v-for="a in rows" :key="a.id" class="card"><span class="badge">{{categories.find(c=>c.code===a.category)?.name}}</span><h3>{{a.title}}</h3><p>{{a.location}}</p><p>{{time(a.startsAt)}}</p><div class="card-bottom"><span>余位 {{a.availableSeats}} / {{a.capacity}}</span><button :disabled="busy" @click="openActivity(a.id)">查看详情</button></div></article></div>
    <article v-if="detail" class="card detail"><h2>{{detail.title}}</h2><p class="description">{{detail.description}}</p><dl><dt>地点</dt><dd>{{detail.location}}</dd><dt>活动时间</dt><dd>{{time(detail.startsAt)}} — {{time(detail.endsAt)}}</dd><dt>报名截止</dt><dd>{{time(detail.registrationClosesAt)}}</dd><dt>人数</dt><dd>{{detail.registeredCount}} / {{detail.capacity}}</dd></dl><button class="primary" :disabled="busy||detail.registrationEligibility!=='OPEN'" @click="signup">{{eligibility[detail.registrationEligibility]}}</button></article>
   </section>
   <section v-if="tab==='mine'"><h2>我的报名</h2><p v-if="!rows.length" class="empty">还没有报名记录</p><article v-for="r in rows" :key="r.id" class="card record"><div><span class="badge">{{states[r.displayStatus]}}</span><h3>{{r.activity.title}}</h3><p>{{time(r.activity.startsAt)}} · {{r.activity.location}}</p><small>报名记录 {{r.id}} · {{time(r.registeredAt)}}</small></div><div class="actions"><button :disabled="busy" @click="checkinView(r)">查看签到</button><button v-if="r.canCancel" :disabled="busy" @click="cancelRegistration(r)">取消报名</button></div></article></section>
   <section v-if="tab==='manage'"><h2>社团活动管理</h2><form class="toolbar" @submit.prevent="run(load)"><label>社团<select v-model="clubId"><option value="1">读书社</option><option value="2">科技社</option></select></label><button :disabled="busy">加载活动</button></form>
    <form class="card form-grid" @submit.prevent="save"><h3 class="full">{{editing?'编辑活动':'创建活动草稿'}}</h3><label>标题<input v-model="form.title" required maxlength="100"></label><label>分类<select v-model="form.category"><option v-for="c in categories" :value="c.code">{{c.name}}</option></select></label><label>地点<input v-model="form.location" required maxlength="200"></label><label>人数上限<input v-model.number="form.capacity" type="number" min="1" step="1" required></label><label>报名截止<input v-model="form.registrationClosesAt" type="datetime-local" required></label><label>开始时间<input v-model="form.startsAt" type="datetime-local" required></label><label>结束时间<input v-model="form.endsAt" type="datetime-local" required></label><label class="full">活动简介<textarea v-model="form.description" required maxlength="4000" rows="3"></textarea></label><div class="actions full"><button class="primary" :disabled="busy">保存草稿</button><button type="button" @click="editing=null;form=emptyForm()">重置表单</button></div></form>
    <article v-for="a in rows" :key="a.id" class="card record"><div><span class="badge">{{states[a.status]}}</span><h3>{{a.title}}</h3><p>{{time(a.startsAt)}} · {{a.registeredCount}} / {{a.capacity}}</p></div><div class="actions"><template v-if="['DRAFT','RETURNED'].includes(a.status)"><button :disabled="busy" @click="edit(a)">编辑</button><button :disabled="busy" @click="submit(a)">提交审核</button></template><button :disabled="busy" @click="viewHistory(a)">审核记录</button><button :disabled="busy" @click="viewRoster(a)">报名名单</button><button v-if="a.status==='PUBLISHED'" :disabled="busy" @click="cancelActivity(a)">取消活动</button></div></article>
    <article v-if="history.length" class="card"><h3>审核记录</h3><p v-for="s in history" :key="s.id">第 {{s.id}} 次提审 · {{states[s.status]}} · {{s.review?.comment||'无审核意见'}} · {{time(s.review?.reviewedAt)}}</p></article>
    <article v-if="detail" class="card"><h3>{{detail.title}} · 报名名单</h3><form class="toolbar" @submit.prevent="run(loadRoster)"><select v-model="searchField"><option value="NAME">姓名</option><option value="STUDENT_NO">完整学号</option></select><input v-model="search" placeholder="输入条件，留空查看全部"><button :disabled="busy">搜索</button></form><div class="table-wrap"><table><thead><tr><th>姓名</th><th>学号尾号</th><th>报名状态</th><th>签到时间</th><th>操作</th></tr></thead><tbody><tr v-for="r in roster" :key="r.registrationId"><td>{{r.studentName}}</td><td>{{r.studentNoLast4}}</td><td>{{states[r.status]}}</td><td>{{time(r.checkedInAt)}}</td><td><button v-if="r.status==='REGISTERED'&&detail.status==='PUBLISHED'" :disabled="busy" @click="checkin(r)">确认到场</button></td></tr></tbody></table></div><p v-if="!roster.length" class="empty">暂无匹配的报名记录</p><div class="toolbar" v-if="rosterTotal>20"><button :disabled="busy||rosterPage<=1" @click="run(async()=>{rosterPage--;await loadRoster()})">上一页</button><span>第 {{rosterPage}} 页 · 共 {{rosterTotal}} 条</span><button :disabled="busy||rosterPage*20>=rosterTotal" @click="run(async()=>{rosterPage++;await loadRoster()})">下一页</button></div></article>
   </section>
   <section v-if="tab==='review'"><h2>活动审核</h2><p v-if="!rows.length" class="empty">暂无待审核活动</p><article v-for="s in rows" :key="s.id" class="card record"><div><h3>活动 {{s.activityId}} · 提审 {{s.id}}</h3><p>{{time(s.submittedAt)}}</p></div><div class="actions"><button :disabled="busy" @click="inspect(s)">查看活动</button><button class="primary" :disabled="busy" @click="review(s,'APPROVED')">通过并发布</button><button :disabled="busy" @click="review(s,'RETURNED')">退回修改</button></div></article><article v-if="reviewActivity" class="card"><h3>{{reviewActivity.title}}</h3><p>{{reviewActivity.description}}</p><p>地点 {{reviewActivity.location}} · 上限 {{reviewActivity.capacity}} 人</p><p>报名截止 {{time(reviewActivity.registrationClosesAt)}}</p><p>活动时间 {{time(reviewActivity.startsAt)}} — {{time(reviewActivity.endsAt)}}</p></article></section>
   <section v-if="tab==='roles'"><h2>角色权限</h2><form class="toolbar" @submit.prevent="run(load)"><label>账号 ID<input v-model="targetAccount" required></label><button :disabled="busy">查询授权</button></form><form class="toolbar" @submit.prevent="grant"><select v-model="role"><option value="CLUB_MANAGER">社团负责人</option><option value="REVIEWER">审核老师</option><option value="SYSTEM_ADMIN">系统管理员</option></select><select v-if="role!=='SYSTEM_ADMIN'" v-model="scope"><option value="1">读书社</option><option value="2">科技社</option></select><button class="primary" :disabled="busy">授予角色</button></form><article v-for="g in rows" :key="g.id" class="card record"><div><h3>{{roleNames[g.role]}}</h3><p>范围 {{g.scopeClubId||'授权管理'}} · {{states[g.status]}}</p></div><button v-if="g.status==='ACTIVE'" :disabled="busy" @click="revoke(g)">撤销授权</button></article></section>
   <div class="toolbar" v-if="total>20"><button :disabled="busy||pageNumber<=1" @click="run(async()=>{pageNumber--;await load()})">上一页</button><span>第 {{pageNumber}} 页 · 共 {{total}} 条</span><button :disabled="busy||pageNumber*20>=total" @click="run(async()=>{pageNumber++;await load()})">下一页</button></div>
  </template>
  <div v-if="!me&&notice" class="notice error" role="status">{{notice}}</div>
 </main><footer>校园活动管理平台 · 课程演示</footer>
</template>
