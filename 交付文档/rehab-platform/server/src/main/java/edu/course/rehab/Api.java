package edu.course.rehab;

import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import javax.servlet.http.HttpServletRequest;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.stream.Collectors;
import static edu.course.rehab.RehabService.*;

@RestController
@RequestMapping("/api")
public class Api {
    private final Store db;private final RehabService service;
    public Api(Store db,RehabService service){this.db=db;this.service=service;}
    @GetMapping("/health") public Object health(){return map("status",db.userCount()>0?"ok":"starting","application","rehab-platform","version","0.1.0","dataSource","SIMULATED");}
    @PostMapping("/login") public Object login(@RequestBody Map<String,Object>body){String name=text(body.get("username"),64),password=text(body.get("password"),100);Map<String,Object>u=db.login(name);if(u==null||!new BCryptPasswordEncoder().matches(password,String.valueOf(u.get("password_hash"))))throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"账号或密码不正确");String token=Auth.newToken();db.token(map("token",Auth.hash(token),"user",u.get("id"),"expires",Timestamp.from(Instant.now().plusSeconds(28800))));return map("token",token,"user",db.user(number(u.get("id"))));}
    @GetMapping("/me") public Object me(@RequestAttribute("user")Map<String,Object>u){return u;}
    @PostMapping("/logout") public Object logout(HttpServletRequest r){db.logout(Auth.hash(r.getHeader("Authorization").substring(7)));return map("ok",true);}
    @GetMapping("/exercises") public Object exercises(){return db.exercises();}
    @GetMapping("/trainees") public Object trainees(@RequestAttribute("user")Map<String,Object>u){service.guide(u);return db.trainees(service.guideFilter(u));}
    @GetMapping("/plans") public Object plans(@RequestAttribute("user")Map<String,Object>u){return db.plans(service.traineeFilter(u),service.guideFilter(u));}
    @GetMapping("/plans/{id}") public Object plan(@RequestAttribute("user")Map<String,Object>u,@PathVariable long id){return service.plan(u,id);}
    @PostMapping("/plans") public Object publish(@RequestAttribute("user")Map<String,Object>u,@RequestBody Map<String,Object>body){return service.publish(u,body,null);}
    @PostMapping("/plans/{id}/adjust") public Object adjust(@RequestAttribute("user")Map<String,Object>u,@PathVariable long id,@RequestBody Map<String,Object>body){return service.publish(u,body,id);}
    @GetMapping("/sessions") public Object sessions(@RequestAttribute("user")Map<String,Object>u){return db.sessions(service.traineeFilter(u),service.guideFilter(u));}
    @PostMapping("/sessions") public Object start(@RequestAttribute("user")Map<String,Object>u,@RequestBody Map<String,Object>body){return service.start(u,number(body.get("plan_id")));}
    @PostMapping("/sessions/{id}/simulate") public Object simulate(@RequestAttribute("user")Map<String,Object>u,@PathVariable long id,@RequestBody Map<String,Object>body){return service.simulate(u,id,String.valueOf(body.getOrDefault("scenario","mixed")));}
    @GetMapping("/sessions/{id}") public Object report(@RequestAttribute("user")Map<String,Object>u,@PathVariable long id){return service.report(u,id);}
    @PostMapping("/sessions/{id}/reviews") public Object review(@RequestAttribute("user")Map<String,Object>u,@PathVariable long id,@RequestBody Map<String,Object>body){return service.review(u,id,body);}
    @GetMapping("/overview") public Object overview(@RequestAttribute("user")Map<String,Object>u){
        List<Map<String,Object>>plans=db.plans(service.traineeFilter(u),service.guideFilter(u));List<Map<String,Object>>sessions=db.sessions(service.traineeFilter(u),service.guideFilter(u));
        List<Map<String,Object>>done=sessions.stream().filter(s->!"RUNNING".equals(s.get("status"))).collect(Collectors.toList());long total=done.stream().mapToLong(s->number(s.get("total_reps"))).sum(),valid=done.stream().mapToLong(s->number(s.get("valid_reps"))).sum();
        LocalDate today=LocalDate.now(ZoneId.of("Asia/Shanghai"));List<Map<String,Object>>due=plans.stream().filter(p->!"SUPERSEDED".equals(p.get("status"))&&!LocalDate.parse(p.get("scheduled_date").toString()).isAfter(today)).collect(Collectors.toList());Set<Long>completeIds=done.stream().map(s->number(s.get("plan_id"))).collect(Collectors.toSet());long completed=due.stream().filter(p->completeIds.contains(number(p.get("id")))).count();
        List<Map<String,Object>>trend=new ArrayList<>();for(int i=6;i>=0;i--){String day=today.minusDays(i).toString();long reps=done.stream().filter(s->s.get("started_at").toString().startsWith(day)).mapToLong(s->number(s.get("total_reps"))).sum();trend.add(map("date",day,"reps",reps));}
        return map("plan_count",plans.size(),"due_count",due.size(),"completed_plans",completed,"session_count",done.size(),"total_reps",total,"valid_reps",valid,"pending_reviews",done.stream().filter(s->"COMPLETED".equals(s.get("status"))).count(),"completion_rate",due.isEmpty()?0:Math.round(completed*100.0/due.size()),"trend",trend,"plans",plans,"recent_sessions",done.stream().limit(5).collect(Collectors.toList()));
    }
    @GetMapping("/games") public Object games(@RequestAttribute("user")Map<String,Object>u){return db.games(service.traineeFilter(u),service.guideFilter(u));}
    @PostMapping("/games") public Object game(@RequestAttribute("user")Map<String,Object>u,@RequestBody Map<String,Object>r){need("TRAINEE".equals(role(u)),"请使用训练者账号保存游戏记录");long cycles=number(r.get("cycles")),seconds=number(r.get("duration_seconds"));need(cycles>=1&&cycles<=100&&seconds>=1&&seconds<=3600,"游戏次数或时长无效");Long sid=r.get("session_id")==null?null:number(r.get("session_id"));if(sid!=null)service.session(u,sid);Map<String,Object>row=map("trainee_id",uid(u),"session_id",sid,"cycles",cycles,"duration_seconds",seconds);db.game(row);return row;}
    @GetMapping("/database") public Object database(@RequestAttribute("user")Map<String,Object>u){if(!"ADMIN".equals(role(u)))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"仅管理员可查看数据库概览");String[][]tables={{"app_user","用户与角色"},{"auth_session","登录会话"},{"guide_relation","指导关系"},{"training_baseline","训练基线"},{"exercise_template","动作模板"},{"rule_version","规则版本"},{"training_plan","训练计划"},{"plan_item","计划明细"},{"training_session","训练会话"},{"session_set","训练组"},{"repetition_result","单次动作结果"},{"issue_event","问题事件"},{"feature_sample","抽样特征"},{"review_record","指导复核"},{"plan_adjustment","计划调整"},{"game_record","游戏记录"}};List<Map<String,Object>>out=new ArrayList<>();for(String[]t:tables)out.add(map("table",t[0],"name",t[1],"count",db.countTable(t[0])));return map("tables",out,"explain",db.explain(2),"query","SELECT * FROM training_session WHERE trainee_id = ? ORDER BY started_at DESC","notes","默认 H2 文件数据库；MySQL 模式与迁移脚本见 README。数据库只由 Java 业务层写入。");}
    @GetMapping(value="/sessions/{id}/export",produces="text/csv;charset=UTF-8") public ResponseEntity<String>export(@RequestAttribute("user")Map<String,Object>u,@PathVariable long id){service.session(u,id);StringBuilder csv=new StringBuilder("\uFEFF动作,组次,次数,峰值幅度,耗时秒,幅度达标,节奏达标,躯干稳定,保持达标\r\n");for(Map<String,Object>r:db.reps(id)){for(String key:new String[]{"exercise_name","set_index","rep_index","peak_angle","duration_seconds","amplitude_ok","tempo_ok","trunk_ok","hold_ok"})csv.append('"').append(String.valueOf(r.get(key)).replace("\"","\"\"")).append("\",");csv.setLength(csv.length()-1);csv.append("\r\n");}return ResponseEntity.ok().header("Content-Disposition","attachment; filename=training-"+id+".csv").body(csv.toString());}
}
