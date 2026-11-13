package edu.course.rehab;

import org.apache.ibatis.annotations.*;
import java.util.*;

@Mapper
public interface Store {
    @Select("SELECT COUNT(*) FROM app_user") int userCount();
    @Select("SELECT * FROM app_user WHERE username=#{username}") Map<String,Object> login(String username);
    @Select("SELECT id,username,display_name,role FROM app_user WHERE id=#{id}") Map<String,Object> user(long id);
    @Insert("INSERT INTO app_user(username,password_hash,display_name,role) VALUES(#{username},#{password_hash},#{display_name},#{role})")
    @Options(useGeneratedKeys=true,keyProperty="id") int addUser(Map<String,Object> row);
    @Insert("INSERT INTO auth_session(token_hash,user_id,expires_at) VALUES(#{token},#{user},#{expires})") int token(Map<String,Object> row);
    @Select("SELECT u.id,u.username,u.display_name,u.role FROM auth_session s JOIN app_user u ON u.id=s.user_id WHERE s.token_hash=#{token} AND s.expires_at>CURRENT_TIMESTAMP") Map<String,Object> authenticate(String token);
    @Delete("DELETE FROM auth_session WHERE token_hash=#{token}") int logout(String token);
    @Insert("INSERT INTO guide_relation(guide_id,trainee_id) VALUES(#{guide},#{trainee})") int relation(@Param("guide") long guide,@Param("trainee") long trainee);
    @Select("SELECT COUNT(*) FROM guide_relation WHERE guide_id=#{guide} AND trainee_id=#{trainee}") int owns(@Param("guide") long guide,@Param("trainee") long trainee);
    @Select("<script>SELECT u.id,u.username,u.display_name,b.note AS baseline FROM app_user u LEFT JOIN training_baseline b ON b.trainee_id=u.id WHERE u.role='TRAINEE'<if test='guide != null'> AND u.id IN (SELECT trainee_id FROM guide_relation WHERE guide_id=#{guide})</if> ORDER BY u.id</script>") List<Map<String,Object>> trainees(@Param("guide") Long guide);
    @Insert("INSERT INTO training_baseline(trainee_id,note) VALUES(#{trainee},#{note})") int baseline(@Param("trainee") long trainee,@Param("note") String note);
    @Insert("INSERT INTO exercise_template(code,name,description,camera_view,joint_names) VALUES(#{code},#{name},#{description},#{camera_view},#{joint_names})")
    @Options(useGeneratedKeys=true,keyProperty="id") int addExercise(Map<String,Object> row);
    @Insert("INSERT INTO rule_version(exercise_id,version,target_min,target_max,min_duration,max_duration,trunk_limit) VALUES(#{exercise_id},#{version},#{target_min},#{target_max},#{min_duration},#{max_duration},#{trunk_limit})")
    @Options(useGeneratedKeys=true,keyProperty="id") int addRule(Map<String,Object> row);
    @Select("SELECT e.*,r.id AS rule_id,r.version AS rule_version,r.target_min,r.target_max,r.min_duration,r.max_duration,r.trunk_limit FROM exercise_template e JOIN rule_version r ON r.exercise_id=e.id ORDER BY e.id") List<Map<String,Object>> exercises();
    @Insert("INSERT INTO training_plan(trainee_id,guide_id,title,scheduled_date,version,status,note) VALUES(#{trainee_id},#{guide_id},#{title},#{scheduled_date},#{version},'PUBLISHED',#{note})")
    @Options(useGeneratedKeys=true,keyProperty="id") int addPlan(Map<String,Object> row);
    @Insert("INSERT INTO plan_item(plan_id,exercise_id,rule_id,sets,repetitions,target_min,target_max,rest_seconds,hold_seconds) VALUES(#{plan_id},#{exercise_id},#{rule_id},#{sets},#{repetitions},#{target_min},#{target_max},#{rest_seconds},#{hold_seconds})")
    @Options(useGeneratedKeys=true,keyProperty="id") int addItem(Map<String,Object> row);
    @Select("<script>SELECT p.*,u.display_name AS trainee_name,g.display_name AS guide_name,(SELECT COUNT(*) FROM plan_item i WHERE i.plan_id=p.id) AS exercise_count,(SELECT COUNT(*) FROM training_session s WHERE s.plan_id=p.id AND s.status IN ('COMPLETED','REVIEWED')) AS completed_sessions FROM training_plan p JOIN app_user u ON u.id=p.trainee_id JOIN app_user g ON g.id=p.guide_id WHERE 1=1<if test='trainee != null'> AND p.trainee_id=#{trainee}</if><if test='guide != null'> AND p.trainee_id IN (SELECT trainee_id FROM guide_relation WHERE guide_id=#{guide})</if> ORDER BY p.scheduled_date DESC,p.id DESC</script>")
    List<Map<String,Object>> plans(@Param("trainee") Long trainee,@Param("guide") Long guide);
    @Select("SELECT p.*,u.display_name AS trainee_name FROM training_plan p JOIN app_user u ON u.id=p.trainee_id WHERE p.id=#{id}") Map<String,Object> plan(long id);
    @Select("SELECT id FROM training_plan WHERE id=#{id} FOR UPDATE") Long lockPlan(long id);
    @Select("SELECT i.*,e.code,e.name,e.description,e.camera_view,r.version AS rule_version,r.min_duration,r.max_duration,r.trunk_limit FROM plan_item i JOIN exercise_template e ON e.id=i.exercise_id JOIN rule_version r ON r.id=i.rule_id WHERE i.plan_id=#{id} ORDER BY i.id") List<Map<String,Object>> items(long id);
    @Update("UPDATE training_plan SET status='SUPERSEDED' WHERE id=#{id}") int supersede(long id);
    @Insert("INSERT INTO plan_adjustment(old_plan_id,new_plan_id,guide_id,reason) VALUES(#{old},#{next},#{guide},#{reason})") int adjustment(Map<String,Object> row);
    @Insert("INSERT INTO training_session(plan_id,trainee_id,rule_snapshot,algorithm_version) VALUES(#{plan_id},#{trainee_id},#{rule_snapshot},#{algorithm_version})")
    @Options(useGeneratedKeys=true,keyProperty="id") int addSession(Map<String,Object> row);
    @Select("SELECT s.*,p.title,p.scheduled_date,u.display_name AS trainee_name FROM training_session s JOIN training_plan p ON p.id=s.plan_id JOIN app_user u ON u.id=s.trainee_id WHERE s.id=#{id}") Map<String,Object> session(long id);
    @Select("<script>SELECT s.*,p.title,u.display_name AS trainee_name FROM training_session s JOIN training_plan p ON p.id=s.plan_id JOIN app_user u ON u.id=s.trainee_id WHERE 1=1<if test='trainee != null'> AND s.trainee_id=#{trainee}</if><if test='guide != null'> AND s.trainee_id IN (SELECT trainee_id FROM guide_relation WHERE guide_id=#{guide})</if> ORDER BY s.started_at DESC,s.id DESC</script>")
    List<Map<String,Object>> sessions(@Param("trainee") Long trainee,@Param("guide") Long guide);
    @Select("SELECT id FROM training_session WHERE id=#{id} FOR UPDATE") Long lock(long id);
    @Update("UPDATE training_session SET status='COMPLETED',ended_at=CURRENT_TIMESTAMP,total_reps=#{total},valid_reps=#{valid},issue_count=#{issues},duration_seconds=#{duration},algorithm_version=#{algorithm} WHERE id=#{id}") int complete(Map<String,Object> row);
    @Update("UPDATE training_session SET status='REVIEWED' WHERE id=#{id}") int reviewed(long id);
    @Insert("INSERT INTO session_set(session_id,plan_item_id,set_index) VALUES(#{session_id},#{plan_item_id},#{set_index})")
    @Options(useGeneratedKeys=true,keyProperty="id") int addSet(Map<String,Object> row);
    @Insert("INSERT INTO repetition_result(set_id,rep_index,start_time,end_time,peak_angle,duration_seconds,amplitude_ok,tempo_ok,trunk_ok,hold_ok,complete) VALUES(#{set_id},#{rep_index},#{start_time},#{end_time},#{peak_angle},#{duration_seconds},#{amplitude_ok},#{tempo_ok},#{trunk_ok},#{hold_ok},#{complete})")
    @Options(useGeneratedKeys=true,keyProperty="id") int addRep(Map<String,Object> row);
    @Insert("INSERT INTO issue_event(session_id,repetition_id,event_time,code,message,observed,threshold_value) VALUES(#{session_id},#{repetition_id},#{event_time},#{code},#{message},#{observed},#{threshold_value})") int addEvent(Map<String,Object> row);
    @Insert("<script>INSERT INTO feature_sample(session_id,set_id,sample_time,angle,trunk_offset,visibility,phase) VALUES <foreach collection='samples' item='s' separator=','>(#{session},#{set},#{s.time},#{s.angle},#{s.trunk},#{s.visibility},#{s.phase})</foreach></script>")
    int addSamples(@Param("session") long session,@Param("set") long set,@Param("samples") List<Map<String,Object>> samples);
    @Select("SELECT r.*,ss.set_index,e.name AS exercise_name FROM repetition_result r JOIN session_set ss ON ss.id=r.set_id JOIN plan_item i ON i.id=ss.plan_item_id JOIN exercise_template e ON e.id=i.exercise_id WHERE ss.session_id=#{id} ORDER BY r.start_time") List<Map<String,Object>> reps(long id);
    @Select("SELECT * FROM issue_event WHERE session_id=#{id} ORDER BY event_time") List<Map<String,Object>> events(long id);
    @Select("SELECT f.*,e.code AS exercise_code,e.name AS exercise_name,i.target_min,i.target_max FROM feature_sample f JOIN session_set ss ON ss.id=f.set_id JOIN plan_item i ON i.id=ss.plan_item_id JOIN exercise_template e ON e.id=i.exercise_id WHERE f.session_id=#{id} ORDER BY f.sample_time") List<Map<String,Object>> samples(long id);
    @Select("SELECT r.*,u.display_name AS guide_name FROM review_record r JOIN app_user u ON u.id=r.guide_id WHERE r.session_id=#{id} ORDER BY r.id DESC") List<Map<String,Object>> reviews(long id);
    @Select("SELECT COUNT(*) FROM issue_event WHERE id=#{event} AND session_id=#{session}") int eventExists(@Param("event") long event,@Param("session") long session);
    @Insert("INSERT INTO review_record(session_id,guide_id,event_id,decision,note) VALUES(#{session_id},#{guide_id},#{event_id},#{decision},#{note})") int review(Map<String,Object> row);
    @Insert("INSERT INTO game_record(trainee_id,session_id,cycles,duration_seconds) VALUES(#{trainee_id},#{session_id},#{cycles},#{duration_seconds})")
    @Options(useGeneratedKeys=true,keyProperty="id") int game(Map<String,Object> row);
    @Select("<script>SELECT g.*,u.display_name AS trainee_name FROM game_record g JOIN app_user u ON u.id=g.trainee_id WHERE 1=1<if test='trainee != null'> AND g.trainee_id=#{trainee}</if><if test='guide != null'> AND g.trainee_id IN (SELECT trainee_id FROM guide_relation WHERE guide_id=#{guide})</if> ORDER BY g.id DESC LIMIT 20</script>") List<Map<String,Object>> games(@Param("trainee") Long trainee,@Param("guide") Long guide);
    // table is selected exclusively from a fixed server-side whitelist; never pass request strings.
    @Select("SELECT COUNT(*) FROM ${table}") long countTable(@Param("table") String table);
    @Select("EXPLAIN SELECT * FROM training_session WHERE trainee_id=#{id} ORDER BY started_at DESC") List<Map<String,Object>> explain(long id);
}
