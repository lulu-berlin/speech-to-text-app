// The download screen's only channel to the main process (roadmap item 50, test item 3): the page
// may report progress and ask for a retry, nothing else. A `.cts` file, compiled to CommonJS on
// purpose — a sandboxed preload script cannot be an ES module.
import electron = require('electron');

const bridge: SttBridge = {
  onProgress: (callback) =>
    void electron.ipcRenderer.on('models:progress', (_event, progress: ModelProgress) =>
      callback(progress),
    ),
  onError: (callback) =>
    void electron.ipcRenderer.on('models:error', (_event, message: string) => callback(message)),
  retry: () => electron.ipcRenderer.send('models:retry'),
};

electron.contextBridge.exposeInMainWorld('stt', bridge);
