import { operationProblems } from './manifest';

/** A widget supports one or more of view / create / modify / approve - and nothing else. */
describe('widget operations', () => {
  const base = { sources: ['a.list'], commands: ['a.save', 'a.withdraw'] };

  it('accepts exactly what the sources and commands perform', () => {
    expect(operationProblems({ ...base, operations: ['view', 'modify', 'approve'],
      commandOperations: { 'a.save': 'modify', 'a.withdraw': 'approve' } })).toEqual([]);
  });

  it('refuses an empty list and anything outside the four', () => {
    expect(operationProblems({ sources: [], commands: [], operations: [], commandOperations: {} }).join())
      .toContain('operations is empty');
    expect(operationProblems({ sources: ['a.list'], commands: [], operations: ['view', 'submit' as never], commandOperations: {} }).join())
      .toContain('"submit" is not one of view, create, modify, approve');
  });

  it('needs an operation for every command, and never view', () => {
    const p = operationProblems({ ...base, operations: ['view', 'modify'],
      commandOperations: { 'a.save': 'modify', 'a.withdraw': 'view' as never } }).join();
    expect(p).toContain('"a.withdraw" operation "view" must be create, modify or approve');
    expect(operationProblems({ ...base, operations: ['view', 'modify'], commandOperations: { 'a.save': 'modify' } }).join())
      .toContain('"a.withdraw" has no entry');
  });

  it('refuses operations the widget does not perform, and missing ones', () => {
    const p = operationProblems({ ...base, operations: ['view', 'create', 'modify'],
      commandOperations: { 'a.save': 'modify', 'a.withdraw': 'approve' } }).join();
    expect(p).toContain('missing approve');
    expect(p).toContain('declares create');
  });
});
