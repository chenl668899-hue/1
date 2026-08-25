const { contextBridge, ipcRenderer } = require('electron');
contextBridge.exposeInMainWorld('suikang', {
  loadState: () => ipcRenderer.invoke('state:load'),
  saveState: (state) => ipcRenderer.invoke('state:save', state),
  importExcel: () => ipcRenderer.invoke('excel:import'),
  chooseImage: () => ipcRenderer.invoke('image:choose'),
  exportQuotePdf: (order) => ipcRenderer.invoke('export:quote-pdf', order),
  exportInternalExcel: (order) => ipcRenderer.invoke('export:internal-xlsx', order),
  exportOrdersExcel: (payload) => ipcRenderer.invoke('export:orders-xlsx', payload),
  revealPath: (p) => ipcRenderer.invoke('file:reveal', p)
});
