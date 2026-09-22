/* Tests for firestore.rules (cast_sessions), run against the real rules engine in
   the Firestore emulator using the exact request shapes the TV (REST) and the
   phone (SDK merge writes) send.

   Run from the repo root (needs Java 11+; Android Studio's bundled JBR works):
     JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" \
       firebase emulators:exec --only firestore --project demo-mktv \
       "node firestore-tests/rules.test.js"

   Why this exists: the first version of these rules looked right and would have
   broken casting completely if deployed - it denied the "is this code free?" read
   every session starts with, rejected the diagnostics fields every send writes,
   capped the sealed box far below a real channel pack, and cut sessions off after
   30 minutes. All four fail here against that version. Run this before deploying
   any change to the rules. */
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
    if (uid) headers.Authorization = 'Bearer ' + token(uid);
    const r = await fetch(BASE + path, { method, headers, body: body ? JSON.stringify(body) : undefined });
    return r.status;
}

function doc(fields) {
    const out = {};
    for (const [k, v] of Object.entries(fields)) {
        if (v === null) out[k] = { nullValue: null };
        else if (v instanceof Date) out[k] = { timestampValue: v.toISOString() };
        else if (typeof v === 'number') out[k] = { integerValue: String(v) };
        else out[k] = { stringValue: v };
    }
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

    console.log('session creation:');
    check('collision check on a free code is allowed (404, not 403)',
        await call('GET', '/cast_sessions/free0001', 'tv'), 404);
    check('unauthenticated read refused', await call('GET', '/cast_sessions/free0001', null), 403);
    check('receiver creates a session',
        await call('PATCH', '/cast_sessions/sess0001', 'tv', doc(fresh())), 200);
    check('receiver polls it', await call('GET', '/cast_sessions/sess0001', 'tv'), 200);
    check('nobody can list the collection', await call('GET', '/cast_sessions', 'attacker'), 403);
    check('create with an unknown field refused',
        await call('PATCH', '/cast_sessions/sess0002', 'tv', doc({ ...fresh(), evil: 'x' })), 403);

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
    check('box over the cap refused',
        await call('PATCH', '/cast_sessions/sess0001' + mask(['box']),
            'phone', doc({ box: 'a'.repeat(950000) })), [403, 400]);
    check('smuggling an extra field refused',
        await call('PATCH', '/cast_sessions/sess0001' + mask(['evil']), 'phone', doc({ evil: 'x' })), 403);

    console.log('session lifetime:');
    const old = new Date(Date.now() - 13 * 3600 * 1000);
    await call('PATCH', '/cast_sessions/old00001', 'tv', doc({ ...fresh(), createdAt: old }));
    check('a session past 12h can no longer be read', await call('GET', '/cast_sessions/old00001', 'tv'), 403);
    const mid = new Date(Date.now() - 3 * 3600 * 1000);
    await call('PATCH', '/cast_sessions/mid00001', 'tv', doc({ ...fresh(), createdAt: mid }));
    check('a 3-hour viewing session is still readable', await call('GET', '/cast_sessions/mid00001', 'tv'), 200);
    check('...and still accepts a channel change',
        await call('PATCH', '/cast_sessions/mid00001' + mask(['box', 'senderVersion', 'packSize', 'updatedAt']),
            'phone', send(small)), 200);

    console.log('cleanup:');
    check('either end can delete', await call('DELETE', '/cast_sessions/sess0001', 'tv'), 200);

    console.log('\n' + pass + ' passed, ' + fail + ' failed');
    process.exit(fail ? 1 : 0);
})().catch(e => { console.error(e); process.exit(2); });
