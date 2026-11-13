package edu.course.rehab;

import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import java.time.*;
import java.util.*;
import static edu.course.rehab.RehabService.*;

@Component
public class DataInitializer implements CommandLineRunner {
    private final Store db;
    public DataInitializer(Store db){this.db=db;}
    @Transactional
    public void run(String...args){
        if(db.userCount()>0)return;
        BCryptPasswordEncoder encoder=new BCryptPasswordEncoder();
        for(String[]u:new String[][]{{"teacher","林老师","GUIDE"},{"trainee","张同学","TRAINEE"},{"admin","管理员","ADMIN"},{"teacher2","周老师","GUIDE"},{"trainee2","李同学","TRAINEE"}})db.addUser(map("username",u[0],"display_name",u[1],"role",u[2],"password_hash",encoder.encode("Demo123!")));
        long teacher=number(db.login("teacher").get("id")),trainee=number(db.login("trainee").get("id")),teacher2=number(db.login("teacher2").get("id")),trainee2=number(db.login("trainee2").get("id"));
        db.relation(teacher,trainee);db.relation(teacher2,trainee2);
        db.baseline(trainee,"课程演示档案：右侧肩肘训练；目标幅度为演示任务参数，不作为临床处方。");db.baseline(trainee2,"独立指导关系测试档案，用于验证跨指导者数据隔离。");
        String[][]data={{"shoulder_flexion","肩前屈抬臂","从手臂自然下垂开始，向前抬起并返回起始位置。第一版使用模拟数据展示完整循环与反馈。","侧面机位","肩、肘、髋","70","110"},{"shoulder_abduction","肩外展抬臂","从手臂自然下垂开始，向身体侧面抬起，再缓慢返回。关注幅度、节奏与躯干侧倾。","正面机位","肩、肘、髋","65","105"},{"elbow_flexion","肘关节屈伸","上臂相对躯干保持稳定，完成屈肘和伸肘。曲线表示相对伸直姿势的屈曲幅度。","侧面机位","肩、肘、腕","95","140"}};
        for(String[]x:data){Map<String,Object>e=map("code",x[0],"name",x[1],"description",x[2],"camera_view",x[3],"joint_names",x[4]);db.addExercise(e);db.addRule(map("exercise_id",e.get("id"),"version","1.0","target_min",Double.valueOf(x[5]),"target_max",Double.valueOf(x[6]),"min_duration",2.5,"max_duration",6.0,"trunk_limit",12));}
        java.time.LocalDate today=java.time.LocalDate.now(ZoneId.of("Asia/Shanghai"));
        seedPlan(teacher,trainee,"肩肘基础训练",today,2,6);
        seedPlan(teacher,trainee,"下一阶段巩固训练",today.plusDays(1),1,8);
        seedPlan(teacher2,trainee2,"独立指导者训练计划",today,1,6);
    }
    private void seedPlan(long guide,long trainee,String title,LocalDate date,int count,int reps){
        Map<String,Object>p=map("trainee_id",trainee,"guide_id",guide,"title",title,"scheduled_date",java.sql.Date.valueOf(date),"version",1,"note","课程演示计划，请按屏幕提示完成模拟训练。");db.addPlan(p);
        List<Map<String,Object>> ex=db.exercises();for(int n=0;n<count;n++){Map<String,Object>e=ex.get(n==0?2:0);db.addItem(map("plan_id",p.get("id"),"exercise_id",e.get("id"),"rule_id",e.get("rule_id"),"sets",1,"repetitions",reps,"target_min",e.get("target_min"),"target_max",e.get("target_max"),"rest_seconds",15,"hold_seconds",0));}
    }
}
