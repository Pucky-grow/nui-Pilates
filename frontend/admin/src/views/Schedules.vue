<template>
  <div>
    <el-alert v-if="loadError || optionError" :title="loadError || optionError" type="error" :closable="false" />
    <el-button v-if="loadError || optionError" @click="initialize">重试</el-button>
    <el-card>
      <div class="toolbar">
        <el-date-picker v-model="range" type="daterange" value-format="YYYY-MM-DD" />
        <el-button @click="load">查询课表</el-button>
        <el-button v-if="can('schedule:create')" data-testid="create" type="primary" :disabled="!!optionError" @click="openForm">创建排课</el-button>
        <el-button v-if="can('manager:course:coach:manage')" data-testid="qualifications" @click="openQualifications">授课资格</el-button>
      </div>
      <el-table v-loading="loading" :data="list" :empty-text="loadError ? '加载失败' : '暂无数据'">
        <el-table-column prop="courseName" label="课程" min-width="140" />
        <el-table-column prop="coachName" label="教练" width="100" />
        <el-table-column prop="classroomName" label="教室" width="110" />
        <el-table-column prop="courseDate" label="日期" width="110" />
        <el-table-column label="时间" width="160"><template #default="{row}">{{ row.startTime }}–{{ row.endTime }}</template></el-table-column>
        <el-table-column prop="maxCapacity" label="人数上限" width="90" />
        <el-table-column prop="bookedCount" label="已约" width="65" />
        <el-table-column prop="remaining" label="剩余" width="65" />
        <el-table-column label="状态" min-width="180"><template #default="{row}">{{ states[row.status] || row.status }}<div class="muted">{{ row.unbookableReason || row.managementReason }}</div></template></el-table-column>
        <el-table-column label="操作" width="180"><template #default="{row}">
          <el-button v-if="can('schedule:update') && row.editable" size="small" data-testid="capacity" @click="openCapacity(row)">调整容量</el-button>
          <el-button v-if="can('schedule:cancel')" size="small" type="danger" :disabled="!row.cancellable || busy" data-testid="cancel" @click="cancel(row)">取消</el-button>
        </template></el-table-column>
      </el-table>
      <el-pagination :total="total" :page-size="10" v-model:current-page="page" @current-change="load" layout="total, prev, pager, next" />
    </el-card>
    <el-dialog v-model="dialog" title="创建未来排课" width="560px" :close-on-click-modal="!busy">
      <el-form label-width="100px">
        <el-form-item label="课程"><el-select v-model="form.courseId" placeholder="选择上架课程" @change="changeCourse"><el-option v-for="c in options.courses" :key="c.id" :value="c.id" :label="c.name" /></el-select></el-form-item>
        <el-form-item label="授课教练"><el-select v-model="form.coachId" placeholder="选择有有效资格的教练"><el-option v-for="c in eligibleCoaches" :key="c.id" :value="c.id" :label="c.name" /></el-select><div v-if="form.courseId && !eligibleCoaches.length" class="muted">该课程暂无有效教练，请先在“授课资格”中指派或审批。</div></el-form-item>
        <el-form-item label="教室"><el-select v-model="form.classroomId" placeholder="选择启用教室"><el-option v-for="r in options.classrooms" :key="r.id" :value="r.id" :label="`${r.name}（容量 ${r.capacity}）`" /></el-select></el-form-item>
        <el-form-item label="日期"><el-date-picker v-model="form.courseDate" type="date" value-format="YYYY-MM-DD" :disabled-date="disabledDate" /></el-form-item>
        <el-form-item label="开始时间"><el-time-picker v-model="form.startTime" value-format="HH:mm:ss" format="HH:mm" /></el-form-item>
        <el-form-item label="结束时间"><el-time-picker v-model="form.endTime" value-format="HH:mm:ss" format="HH:mm" /></el-form-item>
        <el-form-item label="人数上限"><span v-if="privateClass">一对一私教 · 1 人</span><el-input-number v-else v-model="form.maxCapacity" :min="1" :max="selectedRoom?.capacity || 1" :precision="0" /></el-form-item>
      </el-form>
      <el-alert v-if="saveError" :title="saveError" type="error" :closable="false" />
      <template #footer><el-button @click="dialog=false" :disabled="busy">返回</el-button><el-button type="primary" data-testid="save" :loading="busy" @click="save">创建</el-button></template>
    </el-dialog>
    <el-dialog v-model="capacityDialog" title="调整人数上限" width="450px">
      <p>{{ selected?.courseName }} · {{ selected?.courseDate }} {{ selected?.startTime }}</p>
      <p>已约 {{ selected?.bookedCount }} 人；有预约历史时仅允许调整容量。</p>
      <span v-if="selected?.courseType === 'PRIVATE'">一对一私教 · 1 人</span>
      <el-input-number v-else v-model="capacity" :min="Math.max(1,selected?.bookedCount || 0)" :precision="0" />
      <el-alert v-if="saveError" :title="saveError" type="error" :closable="false" />
      <template #footer><el-button type="primary" :loading="busy" @click="saveCapacity">保存容量</el-button></template>
    </el-dialog>
    <el-dialog v-model="qualificationDialog" title="课程授课资格" width="650px">
      <el-select v-model="qualificationCourse" placeholder="选择上架课程" @change="loadQualifications"><el-option v-for="c in options.courses" :key="c.id" :value="c.id" :label="c.name" /></el-select>
      <el-alert v-if="qualificationError" :title="qualificationError" type="error" :closable="false" />
      <el-table :data="qualificationRows" v-loading="qualificationLoading" empty-text="请选择课程">
        <el-table-column prop="name" label="启用教练" /><el-table-column prop="status" label="资格状态" />
        <el-table-column label="操作"><template #default="{row}">
          <el-button v-if="row.status !== 'APPROVED'" :loading="busy" @click="grant(row)">{{ row.status==='PENDING'?'审批通过':row.status==='DISABLED'||row.status==='REJECTED'?'恢复资格':'指派资格' }}</el-button>
          <el-button v-else :disabled="busy" @click="disableQualification(row)">停用资格</el-button>
        </template></el-table-column>
      </el-table>
      <p class="muted">停用资格后停止新增预约，已有预约保留。不会自动授权其他课程。</p>
    </el-dialog>
  </div>
</template>
<script setup>
import { ref, computed, onMounted } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import http from '../utils/request';
import { hasAdminPermission as can } from '../utils/adminSession';
const dateAt = offset => new Date(Date.now()+8*3600000+offset*86400000).toISOString().slice(0,10);
const disabledDate = d => `${d.getFullYear()}-${String(d.getMonth()+1).padStart(2,'0')}-${String(d.getDate()).padStart(2,'0')}` < dateAt(0);
const today = dateAt(0), range=ref([today,dateAt(30)]), page=ref(1), list=ref([]), total=ref(0);
const loading=ref(false), loadError=ref(''), optionError=ref(''), busy=ref(false), saveError=ref('');
const dialog=ref(false), form=ref({}), capacityDialog=ref(false), selected=ref(null), capacity=ref(8);
const options=ref({courses:[],coaches:[],classrooms:[],qualifications:[]});
const states={SCHEDULED:'待开课',CANCELLED:'已取消',FINISHED:'已结束'};
const eligibleCoaches=computed(()=>options.value.coaches.filter(c=>options.value.qualifications.some(q=>q.courseId===form.value.courseId && q.coachId===c.id && q.status==='APPROVED')));
const selectedRoom=computed(()=>options.value.classrooms.find(r=>r.id===form.value.classroomId));
const privateClass=computed(()=>options.value.courses.some(c=>c.id===form.value.courseId&&c.type==='PRIVATE'));
const changeCourse=()=>{form.value.coachId=null;form.value.maxCapacity=privateClass.value?1:8;};
const load=async()=>{loading.value=true;loadError.value='';try{const d=await http.get('/admin/schedules',{params:{page:page.value,size:10,fromDate:range.value?.[0],toDate:range.value?.[1]}});list.value=d.list;total.value=d.total;}catch(e){loadError.value=e?.message||'加载失败，请重试';}finally{loading.value=false;}};
const loadOptions=async()=>{optionError.value='';try{const d=await http.get('/admin/schedules/options');options.value={courses:d.courses||[],coaches:d.coaches||[],classrooms:d.classrooms||[],qualifications:d.qualifications||[]};}catch(e){optionError.value=e?.message||'选项加载失败，请重试';}};
const initialize=()=>Promise.all([load(),loadOptions()]);
const openForm=()=>{form.value={courseId:null,coachId:null,classroomId:null,courseDate:dateAt(1),startTime:'09:00:00',endTime:'10:00:00',maxCapacity:8};saveError.value='';dialog.value=true;};
const payload=row=>({courseId:row.courseId,coachId:row.coachId,classroomId:row.classroomId,courseDate:row.courseDate,startTime:row.startTime,endTime:row.endTime,maxCapacity:row.maxCapacity});
const save=async()=>{
 if(busy.value)return;saveError.value='';
 if(!form.value.courseId||!eligibleCoaches.value.some(c=>c.id===form.value.coachId)||!selectedRoom.value){saveError.value='请选择课程、有资格的教练和教室';return;}
 if(!Number.isInteger(form.value.maxCapacity)||form.value.maxCapacity<1||form.value.maxCapacity>selectedRoom.value.capacity){saveError.value='人数须为正整数，且不超过教室容量';return;}
 busy.value=true;try{await http.post('/admin/schedules',payload(form.value));dialog.value=false;ElMessage.success('已创建排课');await load();}catch(e){saveError.value=e?.message||'创建失败，请重试';}finally{busy.value=false;}
};
const openCapacity=row=>{selected.value=row;capacity.value=row.maxCapacity;saveError.value='';capacityDialog.value=true;};
const saveCapacity=async()=>{if(busy.value)return;busy.value=true;saveError.value='';try{await http.put('/admin/schedules/'+selected.value.id,{...payload(selected.value),maxCapacity:capacity.value});capacityDialog.value=false;await load();}catch(e){saveError.value=e?.message||'保存失败，请刷新后重试';await load();}finally{busy.value=false;}};
const cancel=async row=>{if(busy.value)return;try{await ElMessageBox.confirm('取消后将同步退还全部待签到预约。已有签到或已开始的课不能直接取消。','确认取消');}catch{return;}busy.value=true;try{await http.post('/admin/schedules/'+row.id+'/cancel',null,{params:{reason:'管理端取消'}});ElMessage.success('排课已取消，待签到预约已退还');}catch(e){loadError.value=e?.message||'取消失败';}finally{busy.value=false;await load();}};
const qualificationDialog=ref(false),qualificationCourse=ref(null),qualificationRows=ref([]),qualificationError=ref(''),qualificationLoading=ref(false);
const openQualifications=()=>{qualificationDialog.value=true;qualificationError.value='';qualificationRows.value=[];};
const loadQualifications=async()=>{if(!qualificationCourse.value)return;qualificationLoading.value=true;qualificationError.value='';try{const rows=await http.get('/admin/courses/'+qualificationCourse.value+'/coaches');qualificationRows.value=options.value.coaches.map(c=>({...c,status:rows.find(r=>r.coachId===c.id)?.status||'未指派'}));}catch(e){qualificationError.value=e?.message||'资格加载失败';}finally{qualificationLoading.value=false;}};
const grant=async row=>{if(busy.value)return;busy.value=true;try{await http.put('/admin/courses/'+qualificationCourse.value+'/coaches/'+row.id);await Promise.all([loadQualifications(),loadOptions(),load()]);}catch(e){qualificationError.value=e?.message||'授权失败';}finally{busy.value=false;}};
const disableQualification=async row=>{if(busy.value)return;busy.value=true;try{await http.post('/admin/courses/'+qualificationCourse.value+'/coaches/'+row.id+'/disable');await Promise.all([loadQualifications(),loadOptions(),load()]);}catch(e){qualificationError.value=e?.message||'停用失败';}finally{busy.value=false;}};
onMounted(initialize);
</script>
<style scoped>.toolbar{display:flex;gap:12px;flex-wrap:wrap;margin-bottom:16px}.muted{font-size:12px;color:#777;margin-top:4px}.el-pagination{margin-top:16px}</style>
