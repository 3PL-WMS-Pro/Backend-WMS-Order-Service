/*
 * Fill in the pickup location on barcoded outbound orders processed directly from the web app.
 *
 * WHY THIS EXISTS
 * ---------------
 * The screen never sent where a barcoded item was picked from, so every item on these orders was
 * recorded with location IN_TRANSIT and the Pick List tab showed "Not captured for this record".
 * Fixed in code (Order Service, Oct 2026): the bin now comes from Inventory's reply to the move.
 *
 * The bin was never lost, though: shipping an item writes a LOCATION_CHANGE transaction for that
 * order (triggerTaskId = the order id) whose beforeLocation is the bin it left. This copies that
 * bin onto the order.
 *
 * WHAT IT DOES
 * ------------
 * For each item on a DIRECT_PROCESSING barcoded order whose recorded location is IN_TRANSIT, it
 * finds the item's departure written for that same order and sets the location to the bin it
 * departed from. Items with no such departure are reported and left as they are.
 *
 * SAFETY
 *  - Dry-run by default. Set APPLY=true to write.
 *  - Every change is recorded in ofr_pickup_location_backfill_audit.
 *  - Only allocations still reading IN_TRANSIT are touched, so re-running is harmless.
 *
 * USAGE (one tenant database at a time)
 *   mongosh "<uri>/wms_pro_tenant_199" --file backfill_direct_pickup_locations.js                    # dry run
 *   mongosh "<uri>/wms_pro_tenant_199" --eval 'APPLY=true' --file backfill_direct_pickup_locations.js
 *
 * TO REVERSE A RUN:
 *   db.ofr_pickup_location_backfill_audit.find({ runAt: ISODate("<runAt printed below>") }).forEach(function (a) {
 *     db.order_fulfillment_requests.updateOne({ _id: a.fulfillmentId },
 *       { $set: { 'lineItems.$[l].allocatedItems.$[i].location': 'IN_TRANSIT' } },
 *       { arrayFilters: [{ 'l.lineItemId': a.lineItemId }, { 'i.storageItemId': a.storageItemId, 'i.location': a.location }] });
 *   });
 */

const apply = (typeof APPLY !== 'undefined') && APPLY === true;
const runAt = new Date();
const OFFSITE = ['IN_TRANSIT', 'CUSTOMER', 'CARRIER'];

print('database : ' + db.getName());
print('mode     : ' + (apply ? 'APPLY (writing)' : 'DRY RUN (no writes)'));
print('runAt    : ' + runAt.toISOString());
print('');

const orders = db.order_fulfillment_requests.find(
  { executionApproach: 'DIRECT_PROCESSING', 'lineItems.allocatedItems.location': 'IN_TRANSIT' },
  { lineItems: 1, ginNumber: 1 }
).toArray();

// Each order's items and the allocations to fill.
const pending = [];
orders.forEach((o) => (o.lineItems || []).forEach((l) => (l.allocatedItems || []).forEach((a) => {
  if (a.location === 'IN_TRANSIT') pending.push({ order: o, line: l, alloc: a });
})));
print('orders             : ' + orders.length);
print('items without a bin: ' + pending.length);

// The departure each item made for its order: the bin it left.
const departures = {};
db.item_transactions.find({
  triggerTaskId: { $in: orders.map((o) => o._id) },
  'afterLocation.locationCode': { $in: OFFSITE },
  'beforeLocation.locationCode': { $nin: OFFSITE.concat([null]) }
}, { storageItemId: 1, triggerTaskId: 1, beforeLocation: 1, timestamp: 1 }).forEach((t) => {
  const k = t.triggerTaskId + '|' + Number(t.storageItemId);
  // An item shipped, returned and shipped again on the same order keeps its first pickup.
  if (!departures[k] || t.timestamp < departures[k].timestamp) departures[k] = t;
});

const fills = [];
let notFound = 0;
pending.forEach((p) => {
  const t = departures[p.order._id + '|' + Number(p.alloc.storageItemId)];
  if (!t) { notFound++; return; }
  fills.push({ fulfillmentId: p.order._id, ginNumber: p.order.ginNumber, lineItemId: p.line.lineItemId,
    storageItemId: p.alloc.storageItemId, location: t.beforeLocation.locationCode });
});

const byWarehouse = {};
fills.forEach((f) => { const w = f.location.split('-')[0]; byWarehouse[w] = (byWarehouse[w] || 0) + 1; });
print('bin found          : ' + fills.length + '  ' + JSON.stringify(byWarehouse));
print('no departure found : ' + notFound + ' (left as IN_TRANSIT)');
print('');
fills.slice(0, 8).forEach((f) => print('  ' + f.fulfillmentId + ' (' + (f.ginNumber || '-') + ')  item ' + f.storageItemId + '  -> ' + f.location));

if (!apply) {
  print('');
  print('Dry run only. Re-run with APPLY=true to fill these in.');
  quit();
}

let written = 0;
const byOrder = {};
fills.forEach((f) => { (byOrder[f.fulfillmentId] = byOrder[f.fulfillmentId] || []).push(f); });
Object.keys(byOrder).forEach((id) => {
  const ops = byOrder[id].map((f) => ({
    updateOne: {
      filter: { _id: id },
      update: { $set: { 'lineItems.$[l].allocatedItems.$[i].location': f.location } },
      arrayFilters: [{ 'l.lineItemId': f.lineItemId }, { 'i.storageItemId': f.storageItemId, 'i.location': 'IN_TRANSIT' }]
    }
  }));
  const res = db.order_fulfillment_requests.bulkWrite(ops, { ordered: false });
  written += res.modifiedCount;
  db.ofr_pickup_location_backfill_audit.insertMany(byOrder[id].map((f) => Object.assign({ runAt: runAt }, f)));
});

print('');
print('filled: ' + written);
