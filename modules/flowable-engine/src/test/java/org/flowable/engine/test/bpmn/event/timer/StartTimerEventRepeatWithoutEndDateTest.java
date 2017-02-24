package org.flowable.engine.test.bpmn.event.timer;

import java.util.Calendar;
import java.util.Date;
import java.util.List;

import org.flowable.engine.common.api.delegate.event.FlowableEvent;
import org.flowable.engine.common.impl.util.DefaultClockImpl;
import org.flowable.engine.common.runtime.Clock;
import org.flowable.engine.delegate.event.FlowableEngineEventType;
import org.flowable.engine.impl.test.PluggableFlowableTestCase;
import org.flowable.engine.runtime.Job;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.engine.task.Task;
import org.flowable.engine.test.api.event.TestFlowableEntityEventListener;

/**
 * @author Vasile Dirla
 */
public class StartTimerEventRepeatWithoutEndDateTest extends PluggableFlowableTestCase {

    private TestFlowableEntityEventListener listener;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        listener = new TestFlowableEntityEventListener(Job.class);
        processEngineConfiguration.getEventDispatcher().addEventListener(listener);
    }

    @Override
    protected void tearDown() throws Exception {
        super.tearDown();

        if (listener != null) {
            processEngineConfiguration.getEventDispatcher().removeEventListener(listener);
        }
    }

    /**
     * Timer repetition
     */
    public void testCycleDateStartTimerEvent() throws Exception {
        Clock previousClock = processEngineConfiguration.getClock();

        Clock testClock = new DefaultClockImpl();

        processEngineConfiguration.setClock(testClock);

        Calendar calendar = Calendar.getInstance();
        calendar.set(2025, Calendar.DECEMBER, 10, 0, 0, 0);
        testClock.setCurrentTime(calendar.getTime());

        // deploy the process
        repositoryService.createDeployment().addClasspathResource("org/flowable/engine/test/bpmn/event/timer/StartTimerEventRepeatWithoutEndDateTest.testCycleDateStartTimerEvent.bpmn20.xml").deploy();
        assertEquals(1, repositoryService.createProcessDefinitionQuery().count());

        // AFTER DEPLOYMENT
        // when the process is deployed there will be created a timerStartEvent
        // job which will wait to be executed.
        List<Job> jobs = managementService.createTimerJobQuery().list();
        assertEquals(1, jobs.size());

        // dueDate should be after 24 hours from the process deployment
        Calendar dueDateCalendar = Calendar.getInstance();
        dueDateCalendar.set(2025, Calendar.DECEMBER, 11, 0, 0, 0);

        // check the due date is inside the 2 seconds range
        assertTrue(Math.abs(dueDateCalendar.getTime().getTime() - jobs.get(0).getDuedate().getTime()) < 2000);

        // No process instances
        List<ProcessInstance> processInstances = runtimeService.createProcessInstanceQuery().list();
        assertEquals(0, processInstances.size());

        // No tasks
        List<Task> tasks = taskService.createTaskQuery().list();
        assertEquals(0, tasks.size());

        // ADVANCE THE CLOCK
        // advance the clock after 9 days from starting the process ->
        // the system will execute the pending job and will create a new one (day by day)
        moveByMinutes((9 * 60 * 24));
        executeJobExecutorForTime(10000, 200);

        // there must be a pending job because the endDate is not reached yet
        assertEquals(1, managementService.createTimerJobQuery().count());

        // After time advanced 9 days there should be 9 process instance started
        processInstances = runtimeService.createProcessInstanceQuery().list();
        assertEquals(9, processInstances.size());

        // 9 task to be executed (the userTask "Task A")
        tasks = taskService.createTaskQuery().list();
        assertEquals(9, tasks.size());

        // one new job will be created (and the old one will be deleted after execution)
        jobs = managementService.createTimerJobQuery().list();
        assertEquals(1, jobs.size());

        // check if the last job to be executed has the dueDate set correctly
        // (10'th repeat after 10 dec. => dueDate must have DueDate = 20 dec.)
        dueDateCalendar = Calendar.getInstance();
        dueDateCalendar.set(2025, Calendar.DECEMBER, 20, 0, 0, 0);
        assertTrue(Math.abs(dueDateCalendar.getTime().getTime() - jobs.get(0).getDuedate().getTime()) < 2000);

        // ADVANCE THE CLOCK SO that all 10 repeats to be executed
        // (last execution)
        moveByMinutes(60 * 24);
        try {
            waitForJobExecutorToProcessAllJobsAndExecutableTimerJobs(2000, 200);
        } catch (Exception e) {
            fail("Because the maximum number of repeats is reached no other jobs are created");
        }

        // After the 10nth startEvent Execution should have 10 process instances started
        // (since the first one was not completed)
        processInstances = runtimeService.createProcessInstanceQuery().list();
        assertEquals(10, processInstances.size());

        // the current job will be deleted after execution and a new one will
        // not be created. (all 10 has already executed)
        jobs = managementService.createTimerJobQuery().list();
        assertEquals(0, jobs.size());
        jobs = managementService.createJobQuery().list();
        assertEquals(0, jobs.size());

        // 10 tasks to be executed (the userTask "Task A")
        // one task for each process instance
        tasks = taskService.createTaskQuery().list();
        assertEquals(10, tasks.size());

        // FINAL CHECK
        // count "timer fired" events
        int timerFiredCount = 0;
        List<FlowableEvent> eventsReceived = listener.getEventsReceived();
        for (FlowableEvent eventReceived : eventsReceived) {
            if (FlowableEngineEventType.TIMER_FIRED == eventReceived.getType()) {
                timerFiredCount++;
            }
        }

        // count "entity created" events
        int eventCreatedCount = 0;
        for (FlowableEvent eventReceived : eventsReceived) {
            if (FlowableEngineEventType.ENTITY_CREATED == eventReceived.getType()) {
                eventCreatedCount++;
            }
        }

        // count "entity deleted" events
        int eventDeletedCount = 0;
        for (FlowableEvent eventReceived : eventsReceived) {
            if (FlowableEngineEventType.ENTITY_DELETED == eventReceived.getType()) {
                eventDeletedCount++;
            }
        }
        assertEquals(10, timerFiredCount); // 10 timers fired
        assertEquals(20, eventCreatedCount); // 20 jobs created, 2 per timer job
        assertEquals(20, eventDeletedCount); // 20 jobs deleted, 2 per timer job

        // for each processInstance
        // let's complete the userTasks where the process is hanging in order to
        // complete the processes.
        for (ProcessInstance processInstance : processInstances) {
            tasks = taskService.createTaskQuery().processInstanceId(processInstance.getProcessInstanceId()).list();
            Task task = tasks.get(0);
            assertEquals("Task A", task.getName());
            assertEquals(1, tasks.size());
            taskService.complete(task.getId());
        }

        // now All the process instances should be completed
        processInstances = runtimeService.createProcessInstanceQuery().list();
        assertEquals(0, processInstances.size());

        // no jobs
        jobs = managementService.createTimerJobQuery().list();
        assertEquals(0, jobs.size());
        jobs = managementService.createJobQuery().list();
        assertEquals(0, jobs.size());

        // no tasks
        tasks = taskService.createTaskQuery().list();
        assertEquals(0, tasks.size());

        listener.clearEventsReceived();
        processEngineConfiguration.setClock(previousClock);

        repositoryService.deleteDeployment(repositoryService.createDeploymentQuery().singleResult().getId(), true);

    }

    private void moveByMinutes(int minutes) throws Exception {
        processEngineConfiguration.getClock().setCurrentTime(new Date(processEngineConfiguration.getClock().getCurrentTime().getTime() + ((minutes * 60 * 1000))));
    }

}