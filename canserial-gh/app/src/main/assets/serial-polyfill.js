// Giả lập Web Serial API (navigator.serial) trong Android WebView,
// chuyển lệnh sang cầu nối AndroidSerial (usb-serial-for-android).
(function () {
  if (window.__serialPolyfilled || !window.AndroidSerial) return;
  window.__serialPolyfilled = true;

  var pending = {};
  var nextId = 1;
  var active = null;

  function b64ToBytes(b64) {
    var bin = atob(b64);
    var out = new Uint8Array(bin.length);
    for (var i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
    return out;
  }
  function bytesToB64(chunk) {
    var bytes = chunk instanceof ArrayBuffer
      ? new Uint8Array(chunk)
      : new Uint8Array(chunk.buffer, chunk.byteOffset, chunk.byteLength);
    var s = '';
    for (var i = 0; i < bytes.length; i++) s += String.fromCharCode(bytes[i]);
    return btoa(s);
  }

  // Android gọi ngược lại các hàm này
  window.__serialResolve = function (id, ok, msg) {
    var p = pending[id];
    if (!p) return;
    delete pending[id];
    if (ok) p.resolve();
    else p.reject(new DOMException(msg || 'Lỗi cổng serial', 'NetworkError'));
  };
  window.__serialData = function (b64) {
    if (active && active._ctrl) {
      try { active._ctrl.enqueue(b64ToBytes(b64)); } catch (e) {}
    }
  };
  window.__serialClosed = function () {
    if (active) active._remoteClosed();
  };

  var serial = new EventTarget();

  class SerialPort extends EventTarget {
    constructor(dev) {
      super();
      this._dev = dev;
      this._open = false;
      this._ctrl = null;
      this.readable = null;
      this.writable = null;
    }
    getInfo() {
      return { usbVendorId: this._dev.vid, usbProductId: this._dev.pid };
    }
    open(opts) {
      var self = this;
      opts = opts || {};
      if (this._open) {
        return Promise.reject(new DOMException('Cổng đã mở', 'InvalidStateError'));
      }
      var id = nextId++;
      return new Promise(function (resolve, reject) {
        pending[id] = { resolve: resolve, reject: reject };
        AndroidSerial.open(id, self._dev.name, opts.baudRate || 9600,
          opts.dataBits || 8, opts.stopBits || 1, opts.parity || 'none');
      }).then(function () {
        self._open = true;
        active = self;
        self.readable = new ReadableStream({
          start: function (c) { self._ctrl = c; },
          cancel: function () {}
        });
        self.writable = new WritableStream({
          write: function (chunk) { AndroidSerial.write(bytesToB64(chunk)); }
        });
      });
    }
    close() {
      if (!this._open) return Promise.resolve();
      this._open = false;
      try { AndroidSerial.close(); } catch (e) {}
      try { if (this._ctrl) this._ctrl.close(); } catch (e) {}
      this._ctrl = null;
      this.readable = null;
      this.writable = null;
      if (active === this) active = null;
      return Promise.resolve();
    }
    _remoteClosed() {
      this.close();
      this.dispatchEvent(new Event('disconnect'));
      serial.dispatchEvent(new Event('disconnect'));
    }
    setSignals() { return Promise.resolve(); }
    getSignals() {
      return Promise.resolve({ dataCarrierDetect: false, clearToSend: false, ringIndicator: false, dataSetReady: false });
    }
    forget() { return this.close(); }
  }

  serial.requestPort = function () {
    var list = [];
    try { list = JSON.parse(AndroidSerial.listDevices()); } catch (e) {}
    if (!list.length) {
      return Promise.reject(new DOMException(
        'Không thấy thiết bị USB-Serial nào. Kiểm tra cáp / OTG.', 'NotFoundError'));
    }
    return Promise.resolve(new SerialPort(list[0])); // lấy thiết bị đầu tiên
  };
  serial.getPorts = function () {
    return Promise.resolve(active ? [active] : []);
  };

  Object.defineProperty(navigator, 'serial', { value: serial, configurable: true });
})();
