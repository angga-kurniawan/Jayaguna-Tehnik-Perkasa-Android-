export class MemoryFirestore {
  data = new Map();
  writes = [];
  queue = Promise.resolve();
  constructor(seed = {}) { for (const [path, value] of Object.entries(seed)) this.data.set(path, value); }
  doc(path) {
    const ref = {path, id: path.split('/').at(-1)};
    ref.get = async () => ({exists: this.data.has(path), id: ref.id, ref,
      data: () => this.data.get(path), get: key => this.data.get(path)?.[key]});
    ref.set = async (value, options) => {
      this.writes.push(path);
      this.data.set(path, options?.merge ? {...this.data.get(path), ...value} : value);
    };
    ref.create = async value => {
      if (this.data.has(path)) throw new Error('already-exists');
      await ref.set(value);
    };
    return ref;
  }
  runTransaction(operation) {
    const run = this.queue.then(() => operation({get: ref => ref.get(), set: (ref, value, options) => {void ref.set(value, options);}}));
    this.queue = run.catch(() => {});
    return run;
  }
}
