// Tauri API polyfill bridging to Python backend via HTTP & SSE
(function() {
  const eventListeners = {};

  const getBaseUrl = () => {
    try {
      if (typeof window !== 'undefined' && window.location && window.location.origin && window.location.origin !== 'null' && window.location.protocol.startsWith('http')) {
        return window.location.origin;
      }
    } catch (_) {}
    return 'http://127.0.0.1:8899';
  };

  const apiUrl = (path) => {
    try {
      return new URL(path, getBaseUrl()).href;
    } catch (_) {
      return path;
    }
  };

  // Connect SSE for background events (progress, etc.)
  function connectSSE() {
    try {
      const url = apiUrl('/api/events');
      const evtSource = new EventSource(url);
      evtSource.onmessage = function(e) {
        try {
          const data = JSON.parse(e.data);
          const eventName = data.event;
          if (eventName === 'download-progress' && window.AndroidBridge && window.AndroidBridge.onDownloadEvent) {
            try { window.AndroidBridge.onDownloadEvent(JSON.stringify(payload)); } catch(_) {}
          }
          if (eventListeners[eventName]) {
            eventListeners[eventName].forEach(cb => {
              try { cb({ payload }); } catch(err) { console.error(err); }
            });
          }
        } catch(err) {
          console.error("SSE parse error:", err);
        }
      };
      evtSource.onerror = function() {
        evtSource.close();
        setTimeout(connectSSE, 2000);
      };
    } catch(e) {
      console.error("SSE connection error:", e);
    }
  }
  connectSSE();

  async function invoke(cmd, args = {}) {
    if (cmd === 'open_folder' && window.AndroidBridge && window.AndroidBridge.openFolder) {
      window.AndroidBridge.openFolder(args.path || '');
      return true;
    }
    const res = await fetch(apiUrl('/api/invoke'), {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ cmd, args })
    });
    const result = await res.json();
    if (result.error) {
      throw new Error(result.error);
    }
    return result.data;
  }

  function listen(eventName, callback) {
    if (!eventListeners[eventName]) {
      eventListeners[eventName] = [];
    }
    eventListeners[eventName].push(callback);
    return Promise.resolve(() => {
      eventListeners[eventName] = eventListeners[eventName].filter(cb => cb !== callback);
    });
  }

  function emit(eventName, payload) {
    if (eventListeners[eventName]) {
      eventListeners[eventName].forEach(cb => {
        try { cb({ payload }); } catch(err) { console.error(err); }
      });
    }
    return Promise.resolve();
  }

  // File dialog & upload handler
  async function openDialog(options = {}) {
    if (options.directory) {
      if (window.AndroidBridge && window.AndroidBridge.pickDirectory) {
        return new Promise((resolve) => {
          window.__onAndroidDirectoryPicked = (path) => {
            delete window.__onAndroidDirectoryPicked;
            resolve(path);
          };
          window.AndroidBridge.pickDirectory();
        });
      }
      const current = options.defaultPath || "/home/deck/Downloads/SteamDownloads";
      const chosen = window.prompt(options.title || "Enter directory path:", current);
      return chosen ? chosen.trim() : null;
    }
    return new Promise((resolve) => {
      let fileInput = document.getElementById('bridge-file-input');
      if (!fileInput) {
        fileInput = document.createElement('input');
        fileInput.id = 'bridge-file-input';
        fileInput.type = 'file';
        fileInput.style.position = 'fixed';
        fileInput.style.top = '-9999px';
        fileInput.style.left = '-9999px';
        fileInput.style.opacity = '0';
        document.body.appendChild(fileInput);
      }
      fileInput.value = '';
      if (options.filters && options.filters.length > 0) {
        const exts = options.filters.flatMap(f => f.extensions || []).map(e => '.' + e).join(',');
        fileInput.accept = exts || '*';
      } else {
        fileInput.removeAttribute('accept');
      }
      fileInput.onchange = async () => {
        if (fileInput.files && fileInput.files.length > 0) {
          const file = fileInput.files[0];
          const reader = new FileReader();
          reader.onload = async () => {
            const dataUrl = reader.result;
            const base64 = dataUrl.split(',')[1];
            try {
              const uploadRes = await fetch(apiUrl('/api/upload_file'), {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ name: file.name, content: base64, is_base64: true })
              });
              const upData = await uploadRes.json();
              resolve(upData.path || file.name);
            } catch (err) {
              console.error("Upload error:", err);
              resolve(null);
            }
          };
          reader.readAsDataURL(file);
        } else {
          resolve(null);
        }
      };
      fileInput.click();
    });
  }

  // Drag and drop handler
  window.addEventListener('DOMContentLoaded', () => {
    window.addEventListener('dragover', (e) => e.preventDefault());
    window.addEventListener('drop', async (e) => {
      e.preventDefault();
      if (e.dataTransfer && e.dataTransfer.files && e.dataTransfer.files.length > 0) {
        const file = e.dataTransfer.files[0];
        const reader = new FileReader();
        reader.onload = async () => {
          const dataUrl = reader.result;
          const base64 = dataUrl.split(',')[1];
          try {
            const uploadRes = await fetch(apiUrl('/api/upload_file'), {
              method: 'POST',
              headers: { 'Content-Type': 'application/json' },
              body: JSON.stringify({ name: file.name, content: base64, is_base64: true })
            });
            const upData = await uploadRes.json();
            if (upData.path) {
              emit('tauri://drag-drop', { paths: [upData.path] });
            }
          } catch(err) {
            console.error("Drop error:", err);
          }
        };
        reader.readAsDataURL(file);
      }
    });
  });

  window.__TAURI__ = {
    core: { invoke },
    event: { listen, emit },
    dialog: { open: openDialog },
    shell: {
      open: (url) => { window.open(url, '_blank'); }
    },
    window: {
      getCurrentWindow: () => ({
        startDragging: () => {},
        minimize: () => {},
        maximize: () => {},
        unmaximize: () => {},
        isMaximized: () => Promise.resolve(false),
        close: () => {}
      })
    }
  };
  window.__TAURI_INTERNALS__ = { invoke };

  // Android environment tag
  if (typeof window.AndroidBridge !== 'undefined' || (typeof navigator !== 'undefined' && /Android/i.test(navigator.userAgent))) {
    document.documentElement.classList.add('is-android');
    window.addEventListener('DOMContentLoaded', () => {
      document.body.classList.add('is-android');
    });
  }
})();

