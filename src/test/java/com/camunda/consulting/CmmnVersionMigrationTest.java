package com.camunda.consulting;

import static org.camunda.bpm.engine.test.assertions.cmmn.CmmnAwareTests.*;
import static org.assertj.core.api.Assertions.*;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import org.camunda.bpm.engine.repository.Deployment;
import org.camunda.bpm.engine.runtime.CaseExecution;
import org.camunda.bpm.engine.runtime.CaseInstance;
import org.camunda.bpm.engine.task.Task;
import org.camunda.bpm.engine.test.junit5.ProcessEngineExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableMap;

@ExtendWith(ProcessEngineExtension.class)
public class CmmnVersionMigrationTest {
  
  private static final Logger LOG = LoggerFactory.getLogger(CmmnVersionMigrationTest.class);
  
  @Test
  public void testDatabaseAccess() throws SQLException {
    Connection connection = DriverManager.getConnection("jdbc:h2:mem:camunda", "sa", "");
    Statement statement = connection.createStatement();
    ResultSet resultSet = statement.executeQuery("select * from ACT_GE_PROPERTY");
    while (resultSet.next()) {
      LOG.info("{}, {}, {}", resultSet.getString("NAME_"), resultSet.getString("VALUE_"), resultSet.getInt("REV_"));      
    }
  }
  
  @Test
  public void testCaseVersion2() {
    LOG.info("Test Version 2:");
    repositoryService().createDeployment().addClasspathResource("testCase-version-2.cmmn").deploy();
    
    CaseInstance caseInstance = caseService().createCaseInstanceByKey("Case_1", ImmutableMap.of("readyToGo", false));
    
    List<Task> tasks = taskService().createTaskQuery().caseInstanceId(caseInstance.getId()).list();
    assertThat(tasks).hasSize(1);
    
    displayCaseSentryParts();
    
    CaseExecution caseExecution = caseService().createCaseExecutionQuery().caseInstanceId(caseInstance.getId()).activityId("CasePlanModel_1").singleResult();
    caseService().setVariable(caseExecution.getId(), "readyToGo", true);
    
    tasks = taskService().createTaskQuery().caseInstanceId(caseInstance.getId()).list();
    
    assertThat(tasks).hasSize(2);
  }

  @Test
  public void testMigration() {
    LOG.info("Test migration:");
    repositoryService().createDeployment().addClasspathResource("testCase-version-1.cmmn").deploy();
    
    displayCaseDefs();
    
    CaseInstance caseInstance = caseService().createCaseInstanceByKey("Case_1");
    assertThat(caseInstance).isActive();
    displayCaseExecutions();
    displayTasks();
    
    Deployment secondDepolyment = repositoryService()
        .createDeployment()
        .addClasspathResource("testCase-version-2.cmmn")
        .deploy();
    LOG.info("second deployment: {}", secondDepolyment);
    displayCaseDefs();
    
    CaseInstance caseInstance2 = caseService()
        .createCaseInstanceByKey("Case_1", ImmutableMap.of(
            "customerName", "number2", 
            "readyToGo", false));
    assertThat(caseInstance2).isActive();
    displayCaseExecutions();
    displayTasks();
    displayCaseSentryParts();
    
    updateCaseDefInTask(caseInstance.getId(), caseInstance2.getCaseDefinitionId());
    displayTasks();
    updateCaseDefInCaseExecution(caseInstance.getId(), caseInstance2.getCaseDefinitionId());
    displayCaseExecutions();

    // insert into act_ru_case_execution 
    // (ID_, REV_, CASE_INST_ID_, SUPER_CASE_EXEC_, SUPER_EXEC_, BUSINESS_KEY_, PARENT_ID_, CASE_DEF_ID_, ACT_ID_, PREV_STATE_, CURRENT_STATE_, REQUIRED_, TENANT_ID_) 
    // values ('100', 1, '4', null, null, null, '4', 'Case_1:2:9', 'PlanItem_2', 0, 1, false, null);
    updateDatabaseContent(String.format("insert into act_ru_case_execution" 
        + " (ID_, REV_, CASE_INST_ID_, SUPER_CASE_EXEC_, SUPER_EXEC_, BUSINESS_KEY_, PARENT_ID_, CASE_DEF_ID_, ACT_ID_, PREV_STATE_, CURRENT_STATE_, REQUIRED_, TENANT_ID_)"
        + " values ('100', 1, '%s', null, null, null, '%s', '%s', 'PlanItem_2', 0, 1, false, null)",
        caseInstance.getId(), caseInstance.getId(), caseInstance2.getCaseDefinitionId()));

    // insert into act_ru_case_sentry_part 
    // (ID_, REV_, CASE_INST_ID_, CASE_EXEC_ID_, SENTRY_ID_, TYPE_, SOURCE_CASE_EXEC_ID_, STANDARD_EVENT_, SOURCE_, VARIABLE_EVENT_, VARIABLE_NAME_, SATISFIED_, TENANT_ID_) 
    // values ('101',1,'4','4','Sentry_0m595h6','ifPart',null,null,null,null,null,false,null);
    updateDatabaseContent(String.format("insert into act_ru_case_sentry_part"
        + " (ID_, REV_, CASE_INST_ID_, CASE_EXEC_ID_, SENTRY_ID_, TYPE_, SOURCE_CASE_EXEC_ID_, STANDARD_EVENT_, SOURCE_, VARIABLE_EVENT_, VARIABLE_NAME_, SATISFIED_, TENANT_ID_)"
        + " values ('101',1,'%s','%s','Sentry_0m595h6','ifPart',null,null,null,null,null,false,null)",
        caseInstance.getId(), caseInstance.getId()));

    caseInstance = caseService().createCaseInstanceQuery().caseInstanceId(caseInstance.getId()).singleResult();
    assertThat(caseInstance.getCaseDefinitionId()).isEqualTo(caseInstance2.getCaseDefinitionId());
    
    CaseExecution caseExecution = caseService().createCaseExecutionQuery().caseInstanceId(caseInstance.getId()).activityId("CasePlanModel_1").singleResult();
    caseService().setVariable(caseExecution.getId() , "readyToGo", true);
    
    displayTasks();
    displayCaseSentryParts();
    List<Task> tasksOfCase1 = taskService().createTaskQuery().caseInstanceId(caseInstance.getId()).list();
    assertThat(tasksOfCase1).hasSize(2);
  }
  
  private void updateCaseDefInTask(String caseInstanceId, String newCaseDefId) {
    String updateStatement = "update act_ru_task set case_def_id_ = '" + newCaseDefId + "' where case_inst_id_ = '" + caseInstanceId + "'";
    updateDatabaseContent(updateStatement);
  }

  private void updateCaseDefInCaseExecution(String caseInstanceId, String newCaseDefId) {
    String updateStatement = "update act_ru_case_execution set case_def_id_ = '" + newCaseDefId + "' where case_inst_id_ = '" + caseInstanceId + "'";
    updateDatabaseContent(updateStatement);
  }
  
  private void displayCaseSentryParts() {
    LOG.info("Case sentries:");
    displayDatabaseContent("select id_, rev_, case_inst_id_, case_exec_id_, sentry_id_, type_, source_case_exec_id_, standard_event_, source_, variable_event_, variable_name_, satisfied_ from act_ru_case_sentry_part", 
        "id_", "rev_", "case_inst_id_", "case_exec_id_", "sentry_id_", "type_", "source_case_exec_id_", "standard_event_", "source_", "variable_event_", "variable_name_", "satisfied_");
  }
  
  private void displayTasks() {
    LOG.info("Tasks:");
    displayDatabaseContent("select id_, rev_, execution_id_, case_execution_id_, case_inst_id_, case_def_id_, name_ from act_ru_task", 
        "id_", "rev_", "execution_id_", "case_execution_id_", "case_inst_id_", "case_def_id_", "name_");
  }
  
  private void displayCaseExecutions() {
    LOG.info("Case executions:");
    displayDatabaseContent("select id_, rev_, case_inst_id_, super_case_exec_, parent_id_, case_def_id_, act_id_ from act_ru_case_execution", 
        "id_", "rev_", "case_inst_id_", "super_case_exec_", "parent_id_", "case_def_id_", "act_id_");
  }
  
  private void displayCaseDefs() {
    LOG.info("Case definitions:");
    displayDatabaseContent("select id_, rev_, category_, name_, key_, version_, deployment_id_, resource_name_, dgrm_resource_name_ from act_re_case_def", 
        "id_", "rev_", "category_", "name_", "key_", "version_");
  }
  
  private void displayDatabaseContent(String sqlStatement, String... columnNames) {
    try (Connection connection = DriverManager.getConnection("jdbc:h2:mem:camunda", "sa", "")) {
      Statement statement = connection.createStatement();
      ResultSet resultSet = statement.executeQuery(sqlStatement);
      while (resultSet.next()) {
        String logOutput = "";
        for (int i = 0; i < columnNames.length; i++) {
          logOutput = logOutput + columnNames[i] + ":" + resultSet.getString(columnNames[i]) + ", ";
        }
        LOG.info(logOutput);
      }
    } catch (SQLException e) {
      // TODO Auto-generated catch block
      e.printStackTrace();
    }
    
  }

  private void updateDatabaseContent(String updateStatement) {
    try (Connection connection = DriverManager.getConnection("jdbc:h2:mem:camunda", "sa", "")) {
      Statement statement = connection.createStatement();
      int updatedRows = statement.executeUpdate(updateStatement);
      LOG.info("Updated rows: {}", updatedRows);
      assertThat(updatedRows).isGreaterThan(0);
    } catch (SQLException e) {
      // TODO Auto-generated catch block
      e.printStackTrace();
    }
  }

}
