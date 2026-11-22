// 二佬糖水铺 POS – Google Sheet backup + customer points lookup
// 1) Change SECRET below to any password you like (same one goes in the POS app).
// 2) Deploy > New deployment > Web app > Execute as: Me, Who has access: Anyone.
var SECRET = 'CHANGE-ME';

var TABS = {
  Orders:  ['id','Date','Time','No','Items','Subtotal','Discount','Tax','Rounding','Total','Method','Received','Change','Phone','Voided','Note','Shift','Device'],
  Shifts:  ['id','Date','Opened','Opening cash','Closed','Orders','Sales','Expected cash','Counted cash','Difference','Note'],
  Members: ['phone','Name','Points','Visits','Spent','Since'],
  Rewards: ['id','Reward','Points','Value']
};

function sheet_(name) {
  var ss = SpreadsheetApp.getActiveSpreadsheet();
  var sh = ss.getSheetByName(name);
  if (!sh) {
    sh = ss.insertSheet(name);
    sh.appendRow(TABS[name]);
    sh.setFrozenRows(1);
    sh.getRange(1, 1, 1, TABS[name].length).setFontWeight('bold');
    if (name === 'Members' || name === 'Orders') sh.getRange('A:A').setNumberFormat('@'); // keep phone numbers/ids as text
  }
  return sh;
}

function json_(o) {
  return ContentService.createTextOutput(JSON.stringify(o)).setMimeType(ContentService.MimeType.JSON);
}

function doPost(e) {
  var lock = LockService.getScriptLock();
  try {
    lock.waitLock(20000);
    var body = JSON.parse(e.postData.contents);
    if (body.secret !== SECRET) return json_({ok: false, error: 'Wrong password'});
    var kind = body.kind;
    if (!TABS[kind]) return json_({ok: false, error: 'Unknown kind'});
    var sh = sheet_(kind), cols = TABS[kind].length;
    var rows = body.rows || [];
    var last = sh.getLastRow();
    var index = {};
    if (last > 1) {
      var ids = sh.getRange(2, 1, last - 1, 1).getValues();
      for (var i = 0; i < ids.length; i++) index[String(ids[i][0])] = i + 2;
    }
    var appendRows = [];
    rows.forEach(function (r) {
      r = r.slice(0, cols);
      while (r.length < cols) r.push('');
      r[0] = String(r[0]);
      var at = index[r[0]];
      if (at) sh.getRange(at, 1, 1, cols).setValues([r]);
      else appendRows.push(r);
    });
    if (appendRows.length) {
      sh.getRange(sh.getLastRow() + 1, 1, appendRows.length, cols).setNumberFormat('@').setValues(appendRows);
    }
    if (body.replaceAll && kind !== 'Orders') {
      // remove rows that no longer exist in POS (Rewards only)
      var keep = {};
      rows.forEach(function (r) { keep[String(r[0])] = 1; });
      var n = sh.getLastRow();
      for (var j = n; j >= 2; j--) {
        if (!keep[String(sh.getRange(j, 1).getValue())]) sh.deleteRow(j);
      }
    }
    return json_({ok: true, n: rows.length});
  } catch (err) {
    return json_({ok: false, error: String(err)});
  } finally {
    try { lock.releaseLock(); } catch (x) {}
  }
}

// Customer lookup: ?phone=0123456789  -> name (masked), points, rewards. Nothing else is exposed.
function doGet(e) {
  var p = String((e.parameter && e.parameter.phone) || '').replace(/\D/g, '');
  if (!p) return json_({ok: true, hello: 'POS sheet is online'});
  var out = {ok: true, found: false, rewards: []};
  var mem = sheet_('Members'), mv = mem.getDataRange().getValues();
  for (var i = 1; i < mv.length; i++) {
    if (String(mv[i][0]).replace(/\D/g, '') === p) {
      var nm = String(mv[i][1] || '');
      out.found = true;
      out.name = nm ? nm.charAt(0) + new Array(Math.max(nm.length, 2)).join('*') : 'Member';
      out.points = Number(mv[i][2]) || 0;
      break;
    }
  }
  var rw = sheet_('Rewards').getDataRange().getValues();
  for (var k = 1; k < rw.length; k++) out.rewards.push({n: rw[k][1], pts: Number(rw[k][2]) || 0});
  return json_(out);
}
