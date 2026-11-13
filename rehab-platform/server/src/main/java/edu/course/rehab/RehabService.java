package edu.course.rehab;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import java.net.*;
import java.io.*;
import java.util.*;

@Service
public class RehabService {
    private final Store db;
    private final ObjectMapper json;
    private final TransactionTemplate tx;
    @Value("${rehab.algorithm-url}") private String algorithmUrl;
    public RehabService(Store db,ObjectMapper json,org.springframework.transaction.PlatformTransactionManager manager) { this.db=db; this.json=json; this.tx=new TransactionTemplate(manager); }
    public static Map<String,Object> map(Object... pairs) { Map<String,Object> m=new LinkedHashMap<>(); for(int i=0;i<pairs.length;i+=2)m.put(String.valueOf(pairs[i]),pairs[i+1]); return m; }
    public static long number(Object v) { if(v==null) throw bad("缺少必填数字"); try{return Long.parseLong(v.toString());}catch(Exception e){throw bad("数字参数格式不正确");} }
    public static double decimal(Object v) { if(v==null)throw bad("缺少必填参数"); try{double x=Double.parseDouble(v.toString());if(!Double.isFinite(x))throw new NumberFormatException();return x;}catch(Exception e){throw bad("数值参数不正确");} }
    public static String text(Object v,int max) { String s=v==null?"":v.toString().trim(); if(s.isEmpty()||s.length()>max)throw bad("文本不能为空，且不能超过 "+max+" 字"); return s; }
    public static ResponseStatusException bad(String message){return new ResponseStatusException(HttpStatus.BAD_REQUEST,message);}
    public static void need(boolean ok,String message){if(!ok)throw bad(message);}
    public static String role(Map<String,Object> u){return String.valueOf(u.get("role"));}
    public static long uid(Map<String,Object> u){return number(u.get("id"));}
    public void guide(Map<String,Object> u){if(!Arrays.asList("GUIDE","ADMIN").contains(role(u)))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"此操作仅对指导者开放");}
    public void own(Map<String,Object> u,long trainee) {
        if("ADMIN".equals(role(u))||("TRAINEE".equals(role(u))&&uid(u)==trainee)||("GUIDE".equals(role(u))&&db.owns(uid(u),trainee)>0))return;
        throw new ResponseStatusException(HttpStatus.FORBIDDEN,"无权访问该训练者的数据");
    }
    public Long traineeFilter(Map<String,Object> u){return "TRAINEE".equals(role(u))?uid(u):null;}
    public Long guideFilter(Map<String,Object> u){return "GUIDE".equals(role(u))?uid(u):null;}
    public Map<String,Object> plan(Map<String,Object> u,long id){Map<String,Object> p=db.plan(id);if(p==null)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"计划不存在");own(u,number(p.get("trainee_id")));p.put("items",db.items(id));return p;}
    public Map<String,Object> session(Map<String,Object> u,long id){Map<String,Object>s=db.session(id);if(s==null)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"训练会话不存在");own(u,number(s.get("trainee_id")));return s;}
    @SuppressWarnings("unchecked")
    public Map<String,Object> publish(Map<String,Object> u,Map<String,Object> request,Long oldId) {
        guide(u); long trainee=number(request.get("trainee_id")); own(u,trainee);
        Map<String,Object> target=db.user(trainee);need(target!=null&&"TRAINEE".equals(target.get("role")),"请选择有效训练者");
        String title=text(request.get("title"),100), note=text(request.getOrDefault("note","按计划完成训练"),500);
        java.sql.Date date; try{date=java.sql.Date.valueOf(String.valueOf(request.get("scheduled_date")));}catch(Exception e){throw bad("训练日期格式应为 yyyy-MM-dd");}
        Object raw=request.get("items");need(raw instanceof List,"请配置训练动作");List<Map<String,Object>> input=(List<Map<String,Object>>)raw;need(input.size()>0&&input.size()<=3,"每个计划选择 1～3 个动作");
        Map<Long,Map<String,Object>> exercises=new HashMap<>();for(Map<String,Object>x:db.exercises())exercises.put(number(x.get("id")),x);
        List<Map<String,Object>> rows=new ArrayList<>();Set<Long> seen=new HashSet<>();
        for(Map<String,Object> item:input){long e=number(item.get("exercise_id"));need(exercises.containsKey(e)&&seen.add(e),"动作无效或重复");long sets=number(item.get("sets")),reps=number(item.get("repetitions")),rest=number(item.get("rest_seconds"));double min=decimal(item.get("target_min")),max=decimal(item.get("target_max")),hold=decimal(item.getOrDefault("hold_seconds",0));need(sets>=1&&sets<=5&&reps>=1&&reps<=20,"组数应为 1～5，次数应为 1～20");need(min>=0&&max>min&&max<=180&&rest>=0&&rest<=300&&hold>=0&&hold<=10,"幅度、保持或休息参数不正确");double cycleMin=decimal(item.getOrDefault("min_duration",exercises.get(e).get("min_duration"))),cycleMax=decimal(item.getOrDefault("max_duration",exercises.get(e).get("max_duration")));need(cycleMin>=.6&&cycleMax>=cycleMin&&cycleMax<=30&&hold<=cycleMax,"循环耗时应在 0.6～30 秒内，最大值不小于最小值和保持时长");rows.add(map("min_duration",cycleMin,"max_duration",cycleMax,"exercise_id",e,"rule_id",exercises.get(e).get("rule_id"),"sets",sets,"repetitions",reps,"rest_seconds",rest,"target_min",min,"target_max",max,"hold_seconds",hold));}
        return tx.execute(status->{
            int version=1;
            if(oldId!=null){db.lockPlan(oldId);Map<String,Object>old=plan(u,oldId);need(number(old.get("trainee_id"))==trainee,"调整时不能更换训练者");need(!"SUPERSEDED".equals(old.get("status")),"该计划已有后续版本");version=(int)number(old.get("version"))+1;db.supersede(oldId);}
            Map<String,Object> p=map("trainee_id",trainee,"guide_id",uid(u),"title",title,"scheduled_date",date,"version",version,"note",note);db.addPlan(p);
            for(Map<String,Object> item:rows){item.put("plan_id",p.get("id"));db.addItem(item);}
            if(oldId!=null)db.adjustment(map("old",oldId,"next",p.get("id"),"guide",uid(u),"reason",note));
            return plan(u,number(p.get("id")));
        });
    }
    public Map<String,Object> start(Map<String,Object> u,long planId,String source){
        need(Arrays.asList("CAMERA","SIMULATED").contains(source),"采集来源无效");
        need("TRAINEE".equals(role(u)),"请使用训练者账号开始跟练");Map<String,Object>p=plan(u,planId);need("PUBLISHED".equals(p.get("status")),"该计划已被后续版本替代");
        try{String snapshot=json.writeValueAsString(p);return tx.execute(status->{Map<String,Object>s=map("plan_id",planId,"trainee_id",uid(u),"rule_snapshot",snapshot,"algorithm_version","CAMERA".equals(source)?"camera-pending":"rule-demo-1.0");db.addSession(s);db.source(number(s.get("id")),source);return session(u,number(s.get("id")));});}catch(IOException e){throw new IllegalStateException(e);}
    }
    @SuppressWarnings("unchecked")
    public Map<String,Object> simulate(Map<String,Object> u,long id,String scenario){
        Map<String,Object>s=session(u,id);need("TRAINEE".equals(role(u)),"请使用训练者账号完成训练");
        need("SIMULATED".equals(s.get("data_source")),"摄像头会话不能写入模拟结果");
        if(!"RUNNING".equals(s.get("status")))return report(u,id);
        need(Arrays.asList("mixed","standard").contains(scenario),"模拟场景无效");
        Map<String,Object>snapshot;try{snapshot=json.readValue(String.valueOf(s.get("rule_snapshot")),new TypeReference<Map<String,Object>>(){});}catch(IOException e){throw new IllegalStateException(e);}
        List<Map<String,Object>>items=(List<Map<String,Object>>)snapshot.get("items");
        // Remote inference happens outside the database transaction.
        Map<String,Object>analysis=callAlgorithm("/simulate",map("items",items,"scenario",scenario));
        return saveAnalysis(u,id,analysis,null,null);
    }
    @SuppressWarnings("unchecked")
    private Map<String,Object> saveAnalysis(Map<String,Object> u,long id,Map<String,Object> analysis,Map<String,Object> assessment,List<Map<String,Object>> captures) {
        List<Map<String,Object>> sets=(List<Map<String,Object>>)analysis.get("sets");need(sets!=null&&!sets.isEmpty(),"分析服务没有返回动作结果");
        tx.execute(status->{
            db.lock(id);if(!"RUNNING".equals(db.session(id).get("status")))return null;
            int total=0,valid=0,issues=0;double duration=decimal(analysis.get("duration"));
            for(Map<String,Object> set:sets){Map<String,Object> row=map("session_id",id,"plan_item_id",set.get("item_id"),"set_index",set.get("set_index"));db.addSet(row);long setId=number(row.get("id"));
                for(Map<String,Object>rep:(List<Map<String,Object>>)set.get("reps")){rep.put("set_id",setId);db.addRep(rep);total++;if(assessment!=null?Boolean.TRUE.equals(rep.get("task_met")):(Boolean.TRUE.equals(rep.get("amplitude_ok"))&&Boolean.TRUE.equals(rep.get("tempo_ok"))&&Boolean.TRUE.equals(rep.get("trunk_ok"))&&Boolean.TRUE.equals(rep.get("hold_ok"))&&Boolean.TRUE.equals(rep.get("complete"))))valid++;
                    for(Map<String,Object>event:(List<Map<String,Object>>)rep.get("events")){event.put("session_id",id);event.put("repetition_id",rep.get("id"));db.addEvent(event);issues++;}}
                List<Map<String,Object>>samples=(List<Map<String,Object>>)set.get("samples");if(!samples.isEmpty())db.addSamples(id,setId,samples);
            }
            if(assessment!=null) {
                try { db.addObservation(map("session_id",id,"assessment_json",json.writeValueAsString(assessment),"captures_json",json.writeValueAsString(captures))); }
                catch(IOException e) { throw new IllegalStateException(e); }
                db.source(id,"CAMERA");
            }
            db.complete(map("id",id,"total",total,"valid",valid,"issues",issues,"duration",duration,"algorithm",analysis.get("version")));return null;
        });return report(u,id);
    }
    private Map<String,Object>callAlgorithm(String endpoint,Map<String,Object> payload){
        HttpURLConnection c=null;
        try{c=(HttpURLConnection)new URL(algorithmUrl+endpoint).openConnection();c.setRequestMethod("POST");c.setConnectTimeout(3000);c.setReadTimeout(30000);c.setRequestProperty("Content-Type","application/json");c.setDoOutput(true);json.writeValue(c.getOutputStream(),payload);if(c.getResponseCode()==400){Map<String,Object>error=json.readValue(c.getErrorStream(),new TypeReference<Map<String,Object>>(){});throw bad(String.valueOf(error.get("message")));}if(c.getResponseCode()!=200)throw new IOException("Algorithm returned "+c.getResponseCode());return json.readValue(c.getInputStream(),new TypeReference<Map<String,Object>>(){});}catch(IOException e){throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Python 分析服务未就绪，请运行 start.ps1 或启动 algorithm/service.py");}finally{if(c!=null)c.disconnect();}
    }
    public Map<String,Object> resource(String name) {
        try(InputStream in=getClass().getResourceAsStream("/"+name)) { return json.readValue(in,new TypeReference<Map<String,Object>>(){}); }
        catch(IOException e) { throw new IllegalStateException(e); }
    }
    @SuppressWarnings("unchecked")
    public Map<String,Object> capture(Map<String,Object>u,long id,Map<String,Object>body) {
        Map<String,Object>s=session(u,id);
        need("TRAINEE".equals(role(u)),"请使用训练者账号提交采集");
        need("CAMERA".equals(s.get("data_source")),"请创建摄像头会话再提交采集");
        if(!"RUNNING".equals(s.get("status")))return report(u,id);
        String mode=String.valueOf(body.getOrDefault("evaluation_mode","OBSERVATION"));
        need(Arrays.asList("TARGET","OBSERVATION").contains(mode),"评价模式无效");
        need(!"TARGET".equals(mode)||Boolean.TRUE.equals(body.get("target_confirmed")),"目标比较前请确认已与指导者核对本次计划参数");
        need(body.get("captures") instanceof List,"缺少摄像头采集数据");
        List<Map<String,Object>>captures=(List<Map<String,Object>>)body.get("captures");
        need(!captures.isEmpty()&&captures.size()<=15,"需要 1～15 组采集记录");
        for(Map<String,Object>c:captures){
            need(Arrays.asList("RIGHT","LEFT").contains(c.get("side")),"请选择左侧或右侧");
            need(c.get("frames") instanceof List,"采集帧格式错误");
            double width=decimal(c.get("width")),height=decimal(c.get("height"));
            need(width>=100&&width<=4096&&height>=100&&height<=4096,"画面尺寸无效");
        }
        Map<String,Object>subjective=new LinkedHashMap<>();
        Object subjectiveRaw=body.get("subjective");
        if(subjectiveRaw instanceof Map)for(String k:Arrays.asList("pain_before","pain_after")){
            Object v=((Map<?,?>)subjectiveRaw).get(k);if(v!=null){double n=decimal(v);need(n>=0&&n<=10,"自报疼痛应为 0～10");subjective.put(k,n);}
        }
        String stopReason=text(body.getOrDefault("stop_reason","用户结束"),200);
        try {
            Map<String,Object>snapshot=json.readValue(String.valueOf(s.get("rule_snapshot")),new TypeReference<Map<String,Object>>(){});
            Map<String,Object>model=resource("models/manifest.json");
            need(model.get("sha256").equals(body.get("model_sha256")),"采集模型版本不匹配，请刷新页面后重试");
            Map<String,Object>analysis=callAlgorithm("/analyze",map("items",snapshot.get("items"),"captures",captures,"evaluation_mode",mode,"data_source","CAMERA","subjective",subjective,"model",model,"stop_reason",stopReason));
            Map<String,Object>assessment=(Map<String,Object>)analysis.get("assessment");
            assessment.put("references",resource("exercises.json"));
            assessment.put("target_confirmation", "TARGET".equals(mode)?"训练者声明已与指导者确认；本系统未核验临床处方":"未比较个体目标");
            return saveAnalysis(u,id,analysis,assessment,captures);
        }catch(IOException e){throw new IllegalStateException(e);}
    }
    @SuppressWarnings("unchecked")
    public Map<String,Object> report(Map<String,Object>u,long id){
        Map<String,Object>s=session(u,id);s.put("repetitions",db.reps(id));s.put("events",db.events(id));
        List<Map<String,Object>>samples=db.samples(id);s.put("samples",samples);s.put("reviews",db.reviews(id));
        Map<String,Object>record=db.observationBySession(id);
        if(record!=null)try {
            s.put("assessment",json.readValue(String.valueOf(record.get("assessment_json")),new TypeReference<Map<String,Object>>(){}));
            List<Map<String,Object>>captures=json.readValue(String.valueOf(record.get("captures_json")),new TypeReference<List<Map<String,Object>>>(){});
            Map<Long,Map<String,Object>>frames=new HashMap<>();
            for(Map<String,Object>capture:captures)for(Map<String,Object>frame:(List<Map<String,Object>>)capture.get("frames")){
                frame.put("width",capture.get("width"));frame.put("height",capture.get("height"));frame.put("side",capture.get("side"));
                frames.put(Math.round(decimal(frame.get("time"))*10000),frame);
            }
            for(Map<String,Object>sample:samples){Map<String,Object>frame=frames.get(Math.round(decimal(sample.get("sample_time"))*10000));if(frame!=null){sample.put("landmarks",frame.get("landmarks"));sample.put("frame_width",frame.get("width"));sample.put("frame_height",frame.get("height"));sample.put("side",frame.get("side"));}}
        }catch(IOException e){throw new IllegalStateException(e);}
        return s;
    }
    public Map<String,Object> review(Map<String,Object>u,long id,Map<String,Object>r){guide(u);Map<String,Object>s=session(u,id);need(!"RUNNING".equals(s.get("status")),"训练结束后才能复核");String decision=text(r.get("decision"),20),note=text(r.get("note"),1000);need(Arrays.asList("CONFIRMED","REJECTED","COMMENT").contains(decision),"复核决定无效");Long event=r.get("event_id")==null?null:number(r.get("event_id"));need(event==null||db.eventExists(event,id)>0,"问题事件不属于该训练");need(event!=null||"COMMENT".equals(decision),"确认或驳回时请选择具体问题事件");tx.execute(status->{db.review(map("session_id",id,"guide_id",uid(u),"event_id",event,"decision",decision,"note",note));db.reviewed(id);return null;});return report(u,id);}
}
