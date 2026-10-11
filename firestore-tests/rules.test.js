/* Tests for firestore.rules, run against the real rules engine in the Firestore
   emulator. Phase 1 locks down users and codes. Cast, watch party, playback,
   community health, and rewatch notes stay as open as the deployed ruleset, so
   several older cast restrictions are asserted to remain allowed.

   Run from the repo root (needs Java 11+; Android Studio's bundled JBR works):
     JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" \
       firebase emulators:exec --only firestore --project demo-mktv \
       "node firestore-tests/rules.test.js"

   Run this before deploying any change to the rules. The cast restrictions from
   d8b3aa6 are intentionally not enforced in this phase. */
const HOST = 'http://127.0.0.1:8085';
const PROJECT = 'demo-mktv';
const BASE = `${HOST}/v1/projects/${PROJECT}/databases/(default)/documents`;

function b64u(o) { return Buffer.from(JSON.stringify(o)).toString('base64url'); }
function token(uid) {
    const now = Math.floor(Date.now() / 1000);
    return b64u({ alg: 'none', typ: 'JWT' }) + '.' + b64u({
        sub: uid, user_id: uid, iat: now, exp: now + 3600, auth_time: now,
        aud: PROJECT, iss: 'https://securetoken.google.com/' + PROJECT,
        firebase: { sign_in_provider: 'anonymous', identities: {} }
    }) + '.';
}

async function call(method, path, uid, body) {
    const headers = { 'Content-Type': 'application/json' };
    // The emulator treats the exact bearer "owner" as an admin that bypasses rules.
    // A normal uid, including one that happens to be named owner, is a signed-in client.
    if (uid === 'owner') headers.Authorization = 'Bearer owner';
    else if (uid) headers.Authorization = 'Bearer ' + token(uid);
    const r = await fetch(BASE + path, { method, headers, body: body ? JSON.stringify(body) : undefined });
    return r.status;
}

async function readJson(path, uid) {
    const headers = {};
    if (uid === 'owner') headers.Authorization = 'Bearer owner';
    else if (uid) headers.Authorization = 'Bearer ' + token(uid);
    const r = await fetch(BASE + path, { headers });
    return r.json();
}

function value(v) {
    if (v === null) return { nullValue: null };
    if (v instanceof Date) return { timestampValue: v.toISOString() };
    if (typeof v === 'boolean') return { booleanValue: v };
    if (typeof v === 'number') return Number.isInteger(v)
        ? { integerValue: String(v) } : { doubleValue: v };
    if (typeof v === 'string') return { stringValue: v };
    if (Array.isArray(v)) return { arrayValue: { values: v.map(value) } };
    const fields = {};
    for (const [k, item] of Object.entries(v)) fields[k] = value(item);
    return { mapValue: { fields } };
}

function doc(fields) {
    const out = {};
    for (const [k, v] of Object.entries(fields)) out[k] = value(v);
    return { fields: out };
}

let pass = 0, fail = 0;
function check(name, got, want) {
    const ok = Array.isArray(want) ? want.includes(got) : got === want;
    if (ok) { pass++; console.log('  ok   ' + name + ' (' + got + ')'); }
    else { fail++; console.log('  FAIL ' + name + ' - got ' + got + ', want ' + want); }
}

(async () => {
    const fresh = () => ({ createdAt: new Date(), box: null, url: null, title: null });

    console.log('phase 1 cast sessions stay open to a signed-in client:');
    check('collision check on a free code is allowed (404, not 403)',
        await call('GET', '/cast_sessions/free0001', 'tv'), 404);
    check('unauthenticated read refused', await call('GET', '/cast_sessions/free0001', null), 403);
    check('receiver creates a session',
        await call('PATCH', '/cast_sessions/sess0001', 'tv', doc(fresh())), 200);
    check('receiver polls it', await call('GET', '/cast_sessions/sess0001', 'tv'), 200);
    check('a signed-in client can still list cast sessions',
        await call('GET', '/cast_sessions', 'attacker'), 200);
    check('an extra cast field is still allowed',
        await call('PATCH', '/cast_sessions/sess0002', 'tv', doc({ ...fresh(), evil: 'x' })), 200);

    console.log('sending a cast (the phone\'s merge write):');
    const mask = (keys) => '?' + keys.map(k => 'updateMask.fieldPaths=' + k).join('&');
    const send = (box) => doc({ box, senderVersion: '6.61', packSize: 200, updatedAt: new Date() });
    const small = 'a'.repeat(220);
    const withGuide = 'a'.repeat(90578);       /* measured: 200 channels + 3 programmes */
    check('single-channel cast with diagnostics fields',
        await call('PATCH', '/cast_sessions/sess0001' + mask(['box', 'senderVersion', 'packSize', 'updatedAt']),
            'phone', send(small)), 200);
    check('200-channel pack with guide (~90KB)',
        await call('PATCH', '/cast_sessions/sess0001' + mask(['box', 'senderVersion', 'packSize', 'updatedAt']),
            'phone', send(withGuide)), 200);
    check('a large cast box is still allowed',
        await call('PATCH', '/cast_sessions/sess0001' + mask(['box']),
            'phone', doc({ box: 'a'.repeat(950000) })), 200);
    check('an extra field on an existing cast is still allowed',
        await call('PATCH', '/cast_sessions/sess0001' + mask(['evil']), 'phone', doc({ evil: 'x' })), 200);

    console.log('session lifetime:');
    const old = new Date(Date.now() - 13 * 3600 * 1000);
    await call('PATCH', '/cast_sessions/old00001', 'tv', doc({ ...fresh(), createdAt: old }));
    check('a session past 12h is still readable', await call('GET', '/cast_sessions/old00001', 'tv'), 200);
    const mid = new Date(Date.now() - 3 * 3600 * 1000);
    await call('PATCH', '/cast_sessions/mid00001', 'tv', doc({ ...fresh(), createdAt: mid }));
    check('a 3-hour viewing session is still readable', await call('GET', '/cast_sessions/mid00001', 'tv'), 200);
    check('...and still accepts a channel change',
        await call('PATCH', '/cast_sessions/mid00001' + mask(['box', 'senderVersion', 'packSize', 'updatedAt']),
            'phone', send(small)), 200);

    console.log('cleanup and the other deployed collections:');
    check('either end can delete', await call('DELETE', '/cast_sessions/sess0001', 'tv'), 200);
    check('watch parties can still be listed', await call('GET', '/watch_parties', 'tv'), 200);
    check('playback sessions can still be listed', await call('GET', '/playback_sessions', 'tv'), 200);
    check('a community-health event can be written',
        await call('PATCH', '/community_health/hosthash/events/e1', 'tv', doc({ channelName: 'News' })), 200);
    check('community-health events can still be listed',
        await call('GET', '/community_health/hosthash/events', 'tv'), 200);
    check('a community-health parent document stays denied',
        await call('PATCH', '/community_health/hosthash', 'tv', doc({ channelName: 'News' })), 403);
    check('a rewatch note can be written',
        await call('PATCH', '/rewatch_notes/some-title/notes/n1', 'tv', doc({ text: 'hi' })), 200);
    check('rewatch notes can still be listed',
        await call('GET', '/rewatch_notes/some-title/notes', 'tv'), 200);
    check('a rewatch-notes parent document stays denied',
        await call('PATCH', '/rewatch_notes/some-title', 'tv', doc({ text: 'hi' })), 403);

    // Pairing sync. `owner` is the anonymous account whose library it is.
    // `other` is a different account. `sharesPrefix` starts with the same 8
    // characters and must still be unable to take the pairing pointer.
    const owner = 'AbcdefghUSERAAAA';
    const other = 'zzzzzzzzUSERBBBB';
    const sharesPrefix = 'AbcdefghUSERBBBB';
    const library = () => ({
        version: 1,
        syncedAt: Date.now(),
        device: 'Test',
        favoriteChannelIds: [10, 20],
        favoriteCategoryIds: ['news'],
        recentlyWatchedIds: [10],
        favoriteFolders: ['Sports'],
        channelFolders: { '10': 'Sports' },
        favoriteOrder: [10, 20],
        vodProgress: { '5': { watchedMs: 1000, durationMs: 5000 } },
        seriesProgress: {},
        episodesWatched: [{ seriesId: 1, season: 1, episode: 2, watchedAt: 1, watchedMs: 0, durationMs: 0 }],
        mergedFavorites: [{ serverUrl: 'http://provider.example', streamId: 7, folderName: null }],
        mergedFavoriteCategories: [],
        mergedVodFavorites: [],
        mergedSeriesFavorites: [],
        hiddenMergedVodCategories: [],
        hiddenMergedSeriesCategories: []
    });

    console.log('user library:');
    check('signed-out user cannot read a library', await call('GET', '/users/' + owner, null), 403);
    check('owner can store the sync payload', await call('PATCH', '/users/' + owner, owner, doc(library())), 200);
    check('owner can read their own library', await call('GET', '/users/' + owner, owner), 200);
    check('owner can refresh that payload', await call('PATCH', '/users/' + owner, owner, doc(library())), 200);
    check('the paired device can read it', await call('GET', '/users/' + owner, other), 200);
    check('another account cannot overwrite it',
        await call('PATCH', '/users/' + owner, other, doc(library())), 403);
    check('nobody can list libraries', await call('GET', '/users', other), 403);
    check('owner cannot add premium',
        await call('PATCH', '/users/' + owner, owner, doc({ ...library(), premium: true })), 403);
    check('owner cannot add an expiry',
        await call('PATCH', '/users/' + owner, owner, doc({ ...library(), expiresAt: 4102444800000 })), 403);
    check('owner cannot add admin',
        await call('PATCH', '/users/' + owner, owner, doc({ ...library(), admin: true })), 403);
    check('owner cannot add a device limit',
        await call('PATCH', '/users/' + owner, owner, doc({ ...library(), deviceLimit: 99 })), 403);
    check('a string version is rejected',
        await call('PATCH', '/users/' + owner, owner, doc({ ...library(), version: '1' })), 403);
    check('a new library cannot be created with premium',
        await call('PATCH', '/users/PremiumUserAAAAAA', 'PremiumUserAAAAAA',
            doc({ ...library(), premium: true })), 403);
    check('another account cannot create someone else\'s library',
        await call('PATCH', '/users/NotTheirUSERBBBB', other, doc(library())), 403);
    check('a syncedAt of zero is rejected',
        await call('PATCH', '/users/' + owner, owner, doc({ ...library(), syncedAt: 0 })), 403);
    check('a device name past the cap is rejected',
        await call('PATCH', '/users/' + owner, owner, doc({ ...library(), device: 'x'.repeat(121) })), 403);
    check('another account cannot delete the library',
        await call('DELETE', '/users/' + owner, other), 403);

    // v7.24 merges the approved fields and leaves anything already on the document.
    // The next release replaces the document and thereby drops the legacy key.
    const libraryKeys = Object.keys(library());
    check('emulator admin can leave a legacy field on a library',
        await call('PATCH', '/users/' + owner, 'owner', doc({ ...library(), legacyNote: 'keep', premium: true })), 200);
    check('a v7.24 merge of one approved field keeps the legacy field',
        await call('PATCH', '/users/' + owner + mask(['device']), owner, doc({ device: 'Phone' })), 200);
    const afterMerge = await readJson('/users/' + owner, owner);
    check('the legacy field is still stored',
        afterMerge.fields && afterMerge.fields.legacyNote && afterMerge.fields.legacyNote.stringValue === 'keep' ? 200 : 0,
        200);
    check('a v7.24 merge of the whole library keeps the legacy field',
        await call('PATCH', '/users/' + owner + mask(libraryKeys), owner, doc(library())), 200);
    check('the legacy field cannot be changed',
        await call('PATCH', '/users/' + owner + mask(['legacyNote']), owner, doc({ legacyNote: 'changed' })), 403);
    check('the legacy premium flag cannot be changed',
        await call('PATCH', '/users/' + owner + mask(['premium']), owner, doc({ premium: false })), 403);
    check('a new unknown field cannot be added',
        await call('PATCH', '/users/' + owner + mask(['admin']), owner, doc({ admin: true })), 403);
    check('another account cannot modify a library that has a legacy field',
        await call('PATCH', '/users/' + owner + mask(['device']), other, doc({ device: 'Stolen' })), 403);
    check('an oversized device is rejected while a legacy field remains',
        await call('PATCH', '/users/' + owner + mask(['device']), owner, doc({ device: 'x'.repeat(121) })), 403);
    check('a string version is rejected while a legacy field remains',
        await call('PATCH', '/users/' + owner + mask(['version']), owner, doc({ version: '1' })), 403);
    const afterInvalid = await readJson('/users/' + owner, owner);
    check('a rejected write leaves the legacy field untouched',
        afterInvalid.fields && afterInvalid.fields.legacyNote && afterInvalid.fields.legacyNote.stringValue === 'keep'
            && afterInvalid.fields.device && afterInvalid.fields.device.stringValue === 'Test' ? 200 : 0,
        200);
    check('a full replace clears the legacy field',
        await call('PATCH', '/users/' + owner, owner, doc(library())), 200);
    const afterReplace = await readJson('/users/' + owner, owner);
    check('the legacy field is gone after replace',
        afterReplace.fields && afterReplace.fields.legacyNote ? 0 : 200, 200);

    console.log('pairing codes:');
    check('signed-out client cannot read a code', await call('GET', '/codes/abcdefgh', null), 403);
    check('a missing pairing code is not found', await call('GET', '/codes/nosuchcd', other), 404);
    check('nobody can list codes', await call('GET', '/codes', other), 403);
    check('another account cannot publish this prefix',
        await call('PATCH', '/codes/abcdefgh', other, doc({ uid: other })), 403);
    check('owner can publish the pointer',
        await call('PATCH', '/codes/abcdefgh', owner, doc({ uid: owner })), 200);
    check('the other device can resolve it', await call('GET', '/codes/abcdefgh', other), 200);
    check('another account cannot retarget it',
        await call('PATCH', '/codes/abcdefgh', other, doc({ uid: other })), 403);
    check('a second account with the same prefix cannot take it',
        await call('PATCH', '/codes/abcdefgh', sharesPrefix, doc({ uid: sharesPrefix })), 403);
    check('owner can publish the same pointer again',
        await call('PATCH', '/codes/abcdefgh', owner, doc({ uid: owner })), 200);
    check('owner cannot attach an expiry',
        await call('PATCH', '/codes/abcdefgh', owner, doc({ uid: owner, expiresAt: '2099' })), 403);
    check('nobody can delete a code', await call('DELETE', '/codes/abcdefgh', owner), 403);
    check('nobody else can delete it either', await call('DELETE', '/codes/abcdefgh', other), 403);

    // There is no used/redeemedBy/expiry field. The same pointer can be published
    // again by its owner. A second account that shares the prefix cannot.
    const first = 'CollisionUSERONE';
    const second = 'CollisionUSERTWO';
    check('the first account with a shared prefix can publish it',
        await call('PATCH', '/codes/collisio', first, doc({ uid: first })), 200);
    check('the other account with that prefix cannot take it over',
        await call('PATCH', '/codes/collisio', second, doc({ uid: second })), 403);
    check('the first account can publish it again',
        await call('PATCH', '/codes/collisio', first, doc({ uid: first })), 200);

    const seeded = await call('PATCH', '/codes/poisoned', 'owner', doc({ uid: 'not-the-prefix-owner' }));
    check('emulator admin can seed a poisoned pointer', seeded, 200);
    check('the real prefix owner can take a poisoned pointer back',
        await call('PATCH', '/codes/poisoned', 'poisonedUSERAAAA', doc({ uid: 'poisonedUSERAAAA' })), 200);

    check('the owner cannot delete their library', await call('DELETE', '/users/' + owner, owner), 403);

    console.log('\n' + pass + ' passed, ' + fail + ' failed');
    process.exit(fail ? 1 : 0);
})().catch(e => { console.error(e); process.exit(2); });
