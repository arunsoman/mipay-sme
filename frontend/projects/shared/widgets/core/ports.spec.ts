import { firstValueFrom } from 'rxjs';
import { WidgetStateRef } from './context';
import { FixtureDataPort, ScopedDataPort } from './ports';
import tasks from '../kyc/tasks/tasks.widget';
import fixtures from '../kyc/tasks/tasks.fixtures';

/** The port's self-diagnosis, without a component: what is sent and what comes back. */
function scoped() {
  const base = new FixtureDataPort();
  base.use(fixtures, 'default');
  const state = new WidgetStateRef();
  return { port: new ScopedDataPort(base, tasks, state, null, () => 'fixture'), state };
}

describe('widget port self-diagnosis', () => {
  it('flags a request body that does not match what the server expects', async () => {
    const { port, state } = scoped();
    await firstValueFrom(port.command('kyc.tasks.decide', { taskId: 'task-101', body: { verdict: 'yes', note: 42 } }));
    const d = state.diagnostics().find((x) => x.kind === 'request')!;
    expect(d.name).toBe('kyc.tasks.decide (request)');
    expect(d.issues!.map((i) => `${i.level} ${i.path} ${i.expected}`)).toEqual([
      'error $.outcome string',
      'error $.note string | null',
      'warning $.verdict no field "verdict" in object KycDecisionRequestV2',
    ]);
  });

  it('clears the finding once a correct body is sent', async () => {
    const { port, state } = scoped();
    await firstValueFrom(port.command('kyc.tasks.decide', { taskId: 'task-101', body: {} }));
    expect(state.diagnostics().length).toBe(1);
    await firstValueFrom(port.command('kyc.tasks.decide', { taskId: 'task-101', body: { outcome: 'APPROVE', note: null } }));
    expect(state.diagnostics()).toEqual([]);
  });

  it('flags an answer that breaks the response contract', async () => {
    const base = new FixtureDataPort();
    base.use(fixtures, 'default', { 'kyc.tasks.list': [{ id: 1 }] });
    const state = new WidgetStateRef();
    await firstValueFrom(new ScopedDataPort(base, tasks, state, null, () => '').query('kyc.tasks.list'));
    const d = state.diagnostics()[0];
    expect(d.kind).toBe('contract');
    expect(d.issues![0]).toEqual({ path: '$[0].id', level: 'error', expected: 'string | null', got: '1 (number)' });
  });

  it('refuses names the manifest does not declare, and says so', async () => {
    const { port, state } = scoped();
    await expect(firstValueFrom(port.query('kyc.application.get', { orgId: 'x' }))).rejects.toThrow(/does not list/);
    expect(state.diagnostics()[0].kind).toBe('setup');
  });
});
