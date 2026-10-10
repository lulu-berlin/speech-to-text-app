import { createActor } from 'xstate';
import { ConnectionContext } from '../../app/ConnectionContext';
import type { Task } from '../../shared/api/types';
import { commandMachine } from '../../shared/state/commandMachine';
import type { TaskCommand } from '../../shared/state/transitions';

// Runs a task command through the command machine (ADR 0031): one actor per invocation, so
// only commands docs/api/task-transitions.json allows for the task's status are sent. On
// done, the returned task updates the connection store as a task-updated event (item 13:
// "on done, the returned task updates the store"), then `onDone` runs with that task.
export function useTaskCommand(): (
  command: TaskCommand,
  task: Task,
  onDone?: (updated: Task) => void,
) => void {
  const connection = ConnectionContext.useActorRef();
  return (command, task, onDone) => {
    const actor = createActor(commandMachine, {
      input: { command, taskId: task.id, status: task.status },
    });
    actor.subscribe((snapshot) => {
      if (snapshot.matches('done') && snapshot.context.task !== null) {
        connection.send({ type: 'task-updated', task: snapshot.context.task });
        actor.stop();
        onDone?.(snapshot.context.task);
      }
    });
    actor.start();
    actor.send({ type: 'START' });
  };
}
