const http = require('http');
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

const DEFAULT_PORT = Number(process.env.PORT || 7700);
const CONFIG_FILE = path.join(__dirname, 'mock-config.json');
const DATA_DIR = path.join(__dirname, 'data');
const DIRECTORY_FILE = path.join(DATA_DIR, 'mock-directory.json');
const SESSION_TTL_MS = 8 * 60 * 60 * 1000;
const APP_TOKEN_TTL_MS = 24 * 60 * 60 * 1000;
const SPECIAL = new Set([
  '/mock/token', '/sys/login', '/sys/selectDepart', '/sys/user/getUserInfo',
  '/sys/user/queryListByParam', '/sys/user/queryListByRole', '/sys/user/queryListByXnCode',
  '/basic/peopleInfo/queryList', '/basic/peopleInfo/dqdlRyxx', '/sys/user/depTree',
  '/sys/sysDepart/queryDepartTreeSync', '/sys/sysDepart/getDepartName', '/sys/sysDepart/queryDepartListV2',
  '/basic/sysDepart/getDepartChildByDepartId', '/sys/sysDepart/searchBy', '/sys/role/list',
  '/sys/permission/getUserPermissionByToken', '/sys/permission/getPermCode', '/gaw/user/getUserInfo',
  '/gaw/org/getOrgInfo', '/sys/token/exchange', '/jwy/app/token', '/jwy/user/token', '/jwy/user/info'
]);

const sessions = new Map();
const appTokens = new Map();
let mockConfig = { routes: [] };
let autoIncrement = 1000;

class RouteError extends Error {
  constructor(statusCode, message) {
    super(message);
    this.statusCode = statusCode;
  }
}

function loadJson(filePath) {
  const raw = fs.readFileSync(filePath, 'utf8').replace(/^\uFEFF/, '');
  return JSON.parse(raw);
}

function loadConfig() {
  mockConfig = loadJson(CONFIG_FILE);
}

function watchConfig() {
  let timer = null;
  fs.watch(CONFIG_FILE, () => {
    clearTimeout(timer);
    timer = setTimeout(() => {
      try {
        loadConfig();
        console.log('[mock] config reloaded');
      } catch (error) {
        console.error('[mock] config reload failed:', error.message);
      }
    }, 200);
  });
}

function resolveAllRefs(value) {
  if (typeof value === 'string' && value.startsWith('$ref:')) {
    return loadJson(path.join(DATA_DIR, value.slice(5).trim()));
  }
  if (Array.isArray(value)) return value.map(resolveAllRefs);
  if (value && typeof value === 'object') {
    return Object.fromEntries(Object.entries(value).map(([k, v]) => [k, resolveAllRefs(v)]));
  }
  return value;
}

function getPlaceholderValue(key, context) {
  switch (key) {
    case 'timestamp': return Date.now();
    case 'date': return new Date().toISOString().slice(0, 10);
    case 'datetime': return new Date().toISOString();
    case 'randomId': return crypto.randomBytes(16).toString('hex');
    case 'uuid': return crypto.randomUUID();
    case 'autoIncrement': autoIncrement += 1; return autoIncrement;
    default: return context[key];
  }
}

function replacePlaceholders(value, context) {
  if (typeof value === 'string') {
    const exact = value.match(/^\{\{(\w+)\}\}$/);
    if (exact) {
      const resolved = getPlaceholderValue(exact[1], context);
      return resolved === undefined ? value : resolved;
    }
    return value.replace(/\{\{(\w+)\}\}/g, (match, key) => {
      const resolved = getPlaceholderValue(key, context);
      return resolved === undefined ? match : String(resolved);
    });
  }
  if (Array.isArray(value)) return value.map((item) => replacePlaceholders(item, context));
  if (value && typeof value === 'object') {
    return Object.fromEntries(Object.entries(value).map(([k, v]) => [k, replacePlaceholders(v, context)]));
  }
  return value;
}

function buildPathMatcher(routePath) {
  const names = [];
  const pattern = routePath.split('/').filter(Boolean).map((part) => {
    if (part.startsWith(':')) {
      names.push(part.slice(1));
      return '([^/]+)';
    }
    return part.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  }).join('/');
  return { regex: new RegExp(`^/${pattern}$`), names };
}

function matchRoute(method, pathname) {
  for (const route of mockConfig.routes) {
    if ((route.method || 'GET').toUpperCase() !== method.toUpperCase()) continue;
    const matcher = buildPathMatcher(route.path);
    const match = pathname.match(matcher.regex);
    if (!match) continue;
    const params = {};
    matcher.names.forEach((name, index) => { params[name] = decodeURIComponent(match[index + 1]); });
    return { route, params };
  }
  return null;
}

function readBody(req) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    req.on('data', (chunk) => chunks.push(chunk));
    req.on('end', () => {
      const raw = Buffer.concat(chunks).toString('utf8').trim();
      if (!raw) return resolve({});
      const contentType = (req.headers['content-type'] || '').split(';')[0].trim();
      if (contentType === 'application/json') {
        try { return resolve(JSON.parse(raw)); } catch { return reject(new Error('Invalid JSON request body')); }
      }
      const form = {};
      for (const [key, value] of new URLSearchParams(raw).entries()) form[key] = value;
      resolve(form);
    });
    req.on('error', reject);
  });
}

function getTokenFromRequest(req, urlObject) {
  const authHeader = req.headers.authorization || '';
  if (authHeader.toLowerCase().startsWith('bearer ')) return authHeader.slice(7).trim();
  const candidates = [
    req.headers['x-access-token'], req.headers.token, req.headers['x-user-token'], req.headers.usertoken,
    req.headers['gaw-token'], req.headers.gawtoken, urlObject.searchParams.get('token'),
    urlObject.searchParams.get('accessToken'), urlObject.searchParams.get('userToken'), urlObject.searchParams.get('gawToken')
  ];
  return candidates.find((item) => typeof item === 'string' && item.trim()) || '';
}

function getSession(req, urlObject) {
  const token = getTokenFromRequest(req, urlObject);
  if (!token) return null;
  const session = sessions.get(token);
  if (!session) return null;
  if (session.expiresAt < Date.now()) {
    sessions.delete(token);
    return null;
  }
  return session;
}

function sendJson(res, statusCode, payload, headers = {}) {
  res.writeHead(statusCode, {
    'Content-Type': 'application/json; charset=utf-8',
    'Access-Control-Allow-Origin': '*',
    'Access-Control-Allow-Methods': 'GET,POST,PUT,PATCH,DELETE,OPTIONS',
    'Access-Control-Allow-Headers': 'Content-Type,Authorization,X-Access-Token,token,X-User-Token',
    ...headers
  });
  res.end(JSON.stringify(payload, null, 2));
}

function sendError(res, statusCode, message) {
  sendJson(res, statusCode, { success: false, message, code: statusCode, result: null, timestamp: Date.now() });
}

function logRequest(req, statusCode, startedAt) {
  const duration = Date.now() - startedAt;
  console.log(`[${new Date().toISOString()}] ${req.method} ${req.url} -> ${statusCode} (${duration}ms)`);
}

function ok(result, code = 200, message = '') {
  return { success: true, message, code, result, timestamp: Date.now() };
}

function first(...values) {
  for (const value of values) {
    if (value === undefined || value === null) continue;
    if (typeof value === 'string') {
      if (value.trim()) return value.trim();
      continue;
    }
    return value;
  }
  return '';
}

function byPx(a, b) { return Number(a.px || 0) - Number(b.px || 0); }
function loadDirectory() {
  const directory = loadJson(DIRECTORY_FILE);
  return {
    orgs: Array.isArray(directory.orgs) ? directory.orgs.slice().sort(byPx) : [],
    users: Array.isArray(directory.users) ? directory.users : [],
    memberships: Array.isArray(directory.memberships) ? directory.memberships : []
  };
}
function findOrg(directory, orgCode) { return directory.orgs.find((item) => item.orgCode === orgCode) || null; }
function findOrgById(directory, orgId) { return directory.orgs.find((item) => item.id === orgId) || null; }
function childOrgs(directory, parentOrgCode) { return directory.orgs.filter((item) => (item.parentOrgCode || '') === (parentOrgCode || '')).sort(byPx); }
function userMemberships(directory, userId) {
  return directory.memberships.filter((item) => item.userId === userId)
    .sort((a, b) => Number(Boolean(b.isDefault)) - Number(Boolean(a.isDefault)) || String(a.orgCode).localeCompare(String(b.orgCode)));
}
function findUserById(directory, userId) { return directory.users.find((item) => item.id === userId) || null; }
function findUser(directory, identifier) {
  const normalized = String(identifier || '').trim().toLowerCase();
  if (!normalized) return null;
  return directory.users.find((user) => [user.id, user.username, user.workNo, user.code, user.sfzh, user.realname]
    .some((candidate) => String(candidate || '').trim().toLowerCase() === normalized)) || null;
}
function defaultUser(directory) { return directory.users[0] || null; }
function resolveMembership(directory, userId, requestedOrgCode) {
  const memberships = userMemberships(directory, userId);
  if (!memberships.length) throw new RouteError(500, `No org mapping configured for user ${userId}`);
  if (requestedOrgCode) {
    const membership = memberships.find((item) => item.orgCode === requestedOrgCode);
    if (!membership) throw new RouteError(400, `User is not linked to orgCode ${requestedOrgCode}`);
    return membership;
  }
  return memberships.find((item) => item.isDefault) || memberships[0];
}
function reqVal(body, urlObject, keys) {
  for (const key of keys) {
    const bodyValue = body[key];
    if (typeof bodyValue === 'string' && bodyValue.trim()) return bodyValue.trim();
    if (bodyValue !== undefined && bodyValue !== null && typeof bodyValue !== 'string') return bodyValue;
    const queryValue = urlObject.searchParams.get(key);
    if (queryValue && queryValue.trim()) return queryValue.trim();
  }
  return '';
}
function sessionCtx(directory, session) {
  if (!session) throw new RouteError(401, 'Invalid or expired token');
  const user = findUserById(directory, session.userId);
  if (!user) throw new RouteError(401, 'Mock session user not found');
  const membership = resolveMembership(directory, user.id, session.orgCode);
  const org = findOrg(directory, membership.orgCode);
  if (!org) throw new RouteError(500, `Mock org not found for ${membership.orgCode}`);
  return { user, membership, org, memberships: userMemberships(directory, user.id) };
}
function ctxFromRequest(directory, session, body, urlObject, options = {}) {
  const identifier = first(reqVal(body, urlObject, ['username', 'userId', 'workNo', 'code', 'sfzh', 'idCard', 'realname']), options.identifier);
  let user = null;
  if (identifier) {
    user = findUser(directory, identifier);
    if (!user) throw new RouteError(options.notFoundStatusCode || 404, `Mock user not found: ${identifier}`);
  } else if (options.preferSession !== false && session) {
    user = findUserById(directory, session.userId);
  }
  if (!user && options.useDefaultUser !== false) user = defaultUser(directory);
  if (!user) throw new RouteError(404, 'Mock user not configured');
  const requestedOrgCode = first(options.forceOrgCode, reqVal(body, urlObject, ['orgCode', 'departCode', 'currentOrgCode']));
  const sessionOrgCode = session && session.userId === user.id ? session.orgCode : '';
  const membership = resolveMembership(directory, user.id, requestedOrgCode || sessionOrgCode);
  const org = findOrg(directory, membership.orgCode);
  if (!org) throw new RouteError(500, `Mock org not found for ${membership.orgCode}`);
  return { user, membership, org, memberships: userMemberships(directory, user.id) };
}
function orgFromRequest(directory, session, body, urlObject) {
  const orgCode = reqVal(body, urlObject, ['orgCode', 'departCode', 'currentOrgCode']);
  if (orgCode) {
    const org = findOrg(directory, orgCode);
    if (!org) throw new RouteError(404, `Mock org not found: ${orgCode}`);
    return org;
  }
  if (session) return sessionCtx(directory, session).org;
  const user = defaultUser(directory);
  if (!user) throw new RouteError(404, 'Mock user not configured');
  return findOrg(directory, resolveMembership(directory, user.id).orgCode);
}
function createToken(prefix) { return `${prefix}${crypto.randomBytes(24).toString('hex')}`; }
function issueSession(context, options = {}) {
  const session = {
    token: createToken(options.tokenPrefix || 'mock_'),
    tokenKind: options.tokenKind || 'access',
    userId: context.user.id,
    username: context.user.username,
    orgCode: context.org.orgCode,
    orgName: context.org.departName,
    roleCode: context.user.roleCode,
    roleName: context.user.roleName,
    expiresAt: Date.now() + (options.ttlMs || SESSION_TTL_MS)
  };
  sessions.set(session.token, session);
  return session;
}
function issueAppToken(appId) {
  const token = `mock_app_token_${crypto.randomBytes(18).toString('hex')}`;
  const record = { token, appId, expiresAt: Date.now() + APP_TOKEN_TTL_MS };
  appTokens.set(token, record);
  return record;
}
function roleItem(user) { return { id: user.roleId, roleName: user.roleName, roleCode: user.roleCode, roleType: user.roleType, sqsc: 'INF' }; }
function depItem(org) {
  return { id: org.id, parentId: org.parentId || '', departName: org.departName, departNameAbbr: org.departNameAbbr, orgCategory: org.orgCategory, orgType: org.orgType, orgCode: org.orgCode, status: org.status || '1', isJq: org.isJq || '1', bmjz: org.bmjz || '' };
}
function baseUser(user, org) {
  return {
    id: user.id, username: user.username, realname: user.realname, avatar: '', birthday: user.birthday || null,
    sex: user.sex, phone: user.phone, email: user.email || '', telephone: user.telephone || '', workNo: user.workNo,
    orgCode: org.orgCode, orgCodeTxt: org.departName, status: 1, accountId: user.accountId,
    identity: user.identity, userIdentity: Number(user.identity || 0), title: user.title, homePath: user.homePath || '/workbench/pcs'
  };
}
function userListItem(user, org) {
  return {
    id: user.id, username: user.username, realname: user.realname, avatar: '', sex: user.sex, phone: user.phone,
    orgCode: org.orgCode, orgCodeTxt: org.departName, status: 1, delFlag: 0, workNo: user.workNo,
    identity: user.identity, lastLoginTime: new Date().toISOString(), accountId: user.accountId, xfjz: Boolean(user.xfjz)
  };
}
function peopleItem(user, org) {
  return {
    id: `${user.id}-people`, sex_dictText: user.sex === 2 ? '女' : '男', idCard: user.sfzh || user.username,
    type: user.type || '1', officePhone: user.telephone || '0931-8888888', mobilePhone: user.phone,
    departCode: org.orgCode, departCode_dictText: org.departName, name: user.realname, sex: String(user.sex || 1),
    photo: '', updateTime: new Date().toISOString().replace('T', ' ').slice(0, 19), createTime: '2024-01-01 09:00:00',
    post: user.title, indentity: user.identity, indentity_dictText: user.identityText || '民警', peopleStatus: 'zz',
    peopleStatus_dictText: '在职', birthday: user.birthday || '', code: user.code || user.workNo,
    ethnicity: user.ethnicity || '01', ethnicity_dictText: user.ethnicityText || '汉族', mariatal: user.mariatal || '10',
    mariatal_dictText: user.mariatalText || '未婚', type_dictText: user.typeText || '民警', positionRank: user.positionRank || 'gj', age: user.age || ''
  };
}
function menu(user) {
  return [{ path: user.homePath || '/workbench/pcs', component: 'workbench/index', name: 'workbench', id: user.roleId, meta: { title: user.menuTitle || 'Mock Workbench', keepAlive: false } }];
}
function treeNode(directory, org) {
  return { id: org.id, name: org.departName, nameAbbr: org.departNameAbbr, orgCode: org.orgCode, pCode: org.parentOrgCode || null, pId: org.parentId || null, px: Number(org.px || 0), children: childOrgs(directory, org.orgCode).map((child) => treeNode(directory, child)) };
}
function departTree(directory, org) {
  const children = childOrgs(directory, org.orgCode).map((child) => departTree(directory, child));
  return { key: org.id, value: org.id, title: org.departName, isLeaf: children.length === 0, id: org.id, parentId: org.parentId || null, departName: org.departName, departNameAbbr: org.departNameAbbr, orgCategory: org.orgCategory, orgType: org.orgType, orgCode: org.orgCode, status: org.status || '1', delFlag: Number(org.delFlag || 0), signCode: org.signCode || org.orgCode, children, leaf: children.length === 0 };
}
function usersByOrg(directory, orgCode) {
  const userIds = new Set(directory.memberships.filter((item) => item.orgCode === orgCode).map((item) => item.userId));
  return directory.users.filter((user) => userIds.has(user.id));
}
function loginPayload(directory, context, session) {
  const departments = context.memberships.map((item) => findOrg(directory, item.orgCode)).filter(Boolean).map(depItem);
  return ok({
    token: session.token,
    expireAt: session.expiresAt,
    multi_depart: context.memberships.length,
    userInfo: baseUser(context.user, context.org),
    loginUser: {
      id: context.user.id, username: context.user.username, realname: context.user.realname, phone: context.user.phone,
      orgCode: context.org.orgCode, orgName: context.org.departName, workNo: context.user.workNo,
      loginRelation: { token: session.token, multiDepart: context.memberships.length > 1, sysDepartModel: depItem(context.org) },
      sysDepartList: departments, lastLoginTime: new Date().toISOString()
    }
  }, 200, 'login success');
}
function currentUserPayload(directory, context) {
  const departments = context.memberships.map((item) => findOrg(directory, item.orgCode)).filter(Boolean).map(depItem);
  return ok({ userInfo: { ...baseUser(context.user, context.org), roleList: [roleItem(context.user)], departList: departments, userLabelList: Array.isArray(context.user.labels) ? context.user.labels.map((item, index) => ({ id: `${context.user.id}-label-${index + 1}`, userId: context.user.id, labelType: item.labelType, labelCode: item.labelCode })) : [] } });
}
function placeholderCtx(session) {
  const directory = loadDirectory();
  let context = null;
  try { if (session) context = sessionCtx(directory, session); } catch {}
  if (!context) {
    const user = defaultUser(directory);
    if (user) {
      try { context = ctxFromRequest(directory, null, {}, new URL('http://127.0.0.1'), { identifier: user.username }); } catch {}
    }
  }
  return {
    token: session ? session.token : '', expireAt: session ? session.expiresAt : '', username: context ? context.user.username : 'mock-user',
    realname: context ? context.user.realname : 'Mock User', userId: context ? context.user.id : 'mock-user-id',
    phone: context ? context.user.phone : '13800138000', workNo: context ? context.user.workNo : '000000',
    orgCode: context ? context.org.orgCode : '620000040000', orgName: context ? context.org.departName : '模拟单位1',
    roleCode: context ? context.user.roleCode : 'mock_user', roleName: context ? context.user.roleName : '模拟用户'
  };
}
function special(route, body, urlObject, session) {
  const directory = loadDirectory();
  switch (route.path) {
    case '/mock/token': {
      const context = ctxFromRequest(directory, session, body, urlObject);
      const nextSession = issueSession(context, { tokenPrefix: 'mock_' });
      return { session: nextSession, payload: ok({ token: nextSession.token, expireAt: nextSession.expiresAt, tokenType: 'Bearer', username: context.user.username, realname: context.user.realname, orgCode: context.org.orgCode, orgName: context.org.departName }, 200, 'token generated') };
    }
    case '/sys/login': {
      const context = ctxFromRequest(directory, session, body, urlObject, { notFoundStatusCode: 401 });
      const password = reqVal(body, urlObject, ['password']);
      if (password && password !== context.user.password) throw new RouteError(401, 'Username or password is incorrect');
      const nextSession = issueSession(context, { tokenPrefix: 'mock_' });
      return { session: nextSession, payload: loginPayload(directory, context, nextSession) };
    }
    case '/sys/selectDepart': {
      const current = sessionCtx(directory, session);
      const orgCode = reqVal(body, urlObject, ['orgCode', 'departCode']);
      if (!orgCode) throw new RouteError(400, 'orgCode is required');
      const context = ctxFromRequest(directory, session, body, urlObject, { identifier: current.user.username, forceOrgCode: orgCode });
      const nextSession = issueSession(context, { tokenPrefix: 'mock_' });
      sessions.delete(session.token);
      return { session: nextSession, payload: loginPayload(directory, context, nextSession) };
    }
    case '/sys/user/getUserInfo': return { payload: currentUserPayload(directory, sessionCtx(directory, session)) };
    case '/sys/user/queryListByParam': {
      const org = orgFromRequest(directory, session, body, urlObject);
      return { payload: ok(usersByOrg(directory, org.orgCode).map((user) => userListItem(user, org))) };
    }
    case '/sys/user/queryListByRole': {
      const roleLookup = reqVal(body, urlObject, ['roleId', 'roleCode', 'roleName']);
      const result = directory.users.filter((user) => !roleLookup || [user.roleId, user.roleCode, user.roleName].includes(roleLookup)).map((user) => {
        const org = findOrg(directory, resolveMembership(directory, user.id).orgCode);
        return userListItem(user, org);
      });
      return { payload: ok(result) };
    }
    case '/sys/user/queryListByXnCode': {
      const xnCode = reqVal(body, urlObject, ['code', 'xnCode']);
      const userIds = new Set(directory.memberships.filter((item) => !xnCode || item.xnCode === xnCode).map((item) => item.userId));
      const result = directory.users.filter((user) => userIds.has(user.id)).map((user) => {
        const org = findOrg(directory, resolveMembership(directory, user.id).orgCode);
        return userListItem(user, org);
      });
      return { payload: ok(result) };
    }
    case '/basic/peopleInfo/queryList': {
      const org = orgFromRequest(directory, session, body, urlObject);
      const identity = reqVal(body, urlObject, ['indentity', 'identity']);
      const result = usersByOrg(directory, org.orgCode).filter((user) => !identity || String(user.identity) === String(identity)).map((user) => peopleItem(user, org));
      return { payload: ok(result) };
    }
    case '/basic/peopleInfo/dqdlRyxx': {
      const target = findUser(directory, reqVal(body, urlObject, ['idcard', 'idCard', 'username', 'sfzh'])) || defaultUser(directory);
      if (!target) throw new RouteError(404, 'Mock person not found');
      const org = findOrg(directory, resolveMembership(directory, target.id).orgCode);
      return { payload: ok({ records: [peopleItem(target, org)], total: 1, size: 10, current: 1, pages: 1 }) };
    }
    case '/sys/user/depTree': {
      const org = orgFromRequest(directory, session, body, urlObject);
      return { payload: ok(treeNode(directory, org)) };
    }
    case '/sys/sysDepart/queryDepartTreeSync': {
      const pid = reqVal(body, urlObject, ['pid']);
      if (pid) {
        const org = findOrgById(directory, pid);
        if (!org) throw new RouteError(404, `Mock org not found for pid ${pid}`);
        return { payload: ok(childOrgs(directory, org.orgCode).map((child) => departTree(directory, child))) };
      }
      return { payload: ok(directory.orgs.filter((item) => !item.parentOrgCode).map((org) => departTree(directory, org))) };
    }
    case '/sys/sysDepart/getDepartName': {
      const org = orgFromRequest(directory, session, body, urlObject);
      return { payload: { success: true, message: '', code: 0, result: { id: org.id, parentId: org.parentId || '', parentCode: org.parentOrgCode || '', departName: org.departName, departNameEn: org.departNameEn, departNameAbbr: org.departNameAbbr, orgCategory: org.orgCategory, orgType: org.orgType, orgCode: org.orgCode, status: org.status || '1', delFlag: Number(org.delFlag || 0), policeCategory: org.policeCategory || '1', isJq: org.isJq || '1', bmjz: org.bmjz || '', signCode: org.signCode || org.orgCode, px: Number(org.px || 0) }, timestamp: Date.now() } };
    }
    case '/sys/sysDepart/queryDepartListV2': {
      const orgCode = reqVal(body, urlObject, ['orgCode']);
      const type = reqVal(body, urlObject, ['type', 'orgType']);
      const result = directory.orgs.filter((org) => (!orgCode || org.orgCode.startsWith(orgCode) || (org.parentOrgCode || '').startsWith(orgCode)) && (!type || String(org.orgType) === String(type))).map((org) => ({ id: org.id, departName: org.departName, departNameAbbr: org.departNameAbbr, orgCategory: org.orgCategory, orgType: org.orgType, orgCode: org.orgCode, status: org.status || '1' }));
      return { payload: ok(result) };
    }
    case '/basic/sysDepart/getDepartChildByDepartId': {
      const org = findOrgById(directory, reqVal(body, urlObject, ['id', 'departId']));
      if (!org) throw new RouteError(404, 'Mock org not found for id');
      return { payload: ok(childOrgs(directory, org.orgCode).map((child) => ({ departNameAbbr: child.departNameAbbr, orgCategory: child.orgCategory, orgCode: child.orgCode, id: child.id, departName: child.departName, status: child.status || '1' }))) };
    }
    case '/sys/sysDepart/searchBy': {
      const keyword = reqVal(body, urlObject, ['keyWord', 'keyword']);
      const result = directory.orgs.filter((org) => !keyword || org.departName.includes(keyword) || org.departNameAbbr.includes(keyword) || org.orgCode.includes(keyword)).map((org) => {
        const leaf = childOrgs(directory, org.orgCode).length === 0;
        return { key: org.id, title: org.departName, isLeaf: leaf, id: org.id, departName: org.departName, orgCode: org.orgCode, leaf };
      });
      return { payload: { success: true, message: '', code: 0, result, timestamp: Date.now() } };
    }
    case '/sys/role/list': {
      const seen = new Set();
      const records = [];
      for (const user of directory.users) {
        if (seen.has(user.roleCode)) continue;
        seen.add(user.roleCode);
        records.push(roleItem(user));
      }
      return { payload: { success: true, message: '', code: 0, result: { records, total: records.length, size: 10, current: 1, pages: 1 }, timestamp: Date.now() } };
    }
    case '/sys/permission/getUserPermissionByToken': {
      const permissions = Array.isArray(sessionCtx(directory, session).user.permissions) ? sessionCtx(directory, session).user.permissions : [];
      return { payload: { success: true, message: '', code: 0, result: { allAuth: permissions.map((item) => ({ ...item, status: '1' })), auth: permissions.map((item) => ({ action: item.action, describe: item.describe, type: item.type || '1' })), menu: menu(sessionCtx(directory, session).user) }, timestamp: Date.now() } };
    }
    case '/sys/permission/getPermCode': {
      const permissions = Array.isArray(sessionCtx(directory, session).user.permissions) ? sessionCtx(directory, session).user.permissions : [];
      return { payload: { success: true, message: '', code: 0, result: permissions.map((item) => item.action), timestamp: Date.now() } };
    }
    case '/gaw/user/getUserInfo':
    case '/jwy/user/info': {
      const context = ctxFromRequest(directory, session, body, urlObject);
      return { payload: ok({ code: context.user.code || context.user.workNo, sfzh: context.user.sfzh || context.user.username, name: context.user.realname, departCode: context.org.orgCode, departName: context.org.departName }) };
    }
    case '/gaw/org/getOrgInfo': {
      const base = ctxFromRequest(directory, session, body, urlObject);
      const orgCode = reqVal(body, urlObject, ['orgCode']) || base.org.orgCode;
      const org = findOrg(directory, orgCode);
      if (!org) throw new RouteError(404, `Mock org not found: ${orgCode}`);
      return { payload: ok({ parentId: org.parentId || '', parentCode: org.parentOrgCode || '', id: org.id, orgCode: org.orgCode, departName: org.departName }) };
    }
    case '/sys/token/exchange': {
      const context = ctxFromRequest(directory, session, body, urlObject);
      const nextSession = issueSession(context, { tokenPrefix: 'mock_exchanged_token_', tokenKind: 'exchange' });
      return { session: nextSession, payload: ok(nextSession.token) };
    }
    case '/jwy/app/token': {
      const record = issueAppToken(reqVal(body, urlObject, ['appId', 'clientId']) || 'third-party-demo-app');
      return { headers: { 'X-App-Token': record.token }, payload: ok(record.token) };
    }
    case '/jwy/user/token': {
      const context = ctxFromRequest(directory, session, body, urlObject);
      const nextSession = issueSession(context, { tokenPrefix: 'mock_user_token_', tokenKind: 'jwy-user' });
      return { session: nextSession, payload: ok(nextSession.token) };
    }
    default:
      return null;
  }
}

async function handleRequest(req, res) {
  const startedAt = Date.now();
  if (req.method === 'OPTIONS') {
    sendJson(res, 204, {});
    logRequest(req, 204, startedAt);
    return;
  }
  const urlObject = new URL(req.url, 'http://127.0.0.1');
  const matched = matchRoute(req.method, urlObject.pathname);
  if (!matched) {
    sendError(res, 404, `Route not found: ${req.method} ${urlObject.pathname}`);
    logRequest(req, 404, startedAt);
    return;
  }
  let body = {};
  try {
    body = await readBody(req);
  } catch (error) {
    sendError(res, 400, error.message);
    logRequest(req, 400, startedAt);
    return;
  }
  const session = getSession(req, urlObject);
  const { route, params } = matched;
  if (route.requireAuth && !session) {
    sendError(res, 401, 'Invalid or expired token');
    logRequest(req, 401, startedAt);
    return;
  }
  let routeResult = null;
  try {
    routeResult = SPECIAL.has(route.path) ? special(route, body, urlObject, session) : null;
  } catch (error) {
    if (error instanceof RouteError) {
      sendError(res, error.statusCode, error.message);
      logRequest(req, error.statusCode, startedAt);
      return;
    }
    throw error;
  }
  const activeSession = (routeResult && routeResult.session) || session;
  if (route.path === '/sys/logout' && session) sessions.delete(session.token);
  if (route.delay) await new Promise((resolve) => setTimeout(resolve, route.delay));
  const context = { ...params, ...Object.fromEntries(urlObject.searchParams.entries()), ...body, ...placeholderCtx(activeSession) };
  const payload = routeResult ? routeResult.payload : replacePlaceholders(resolveAllRefs(route.response), context);
  const headers = { ...(routeResult && routeResult.headers ? routeResult.headers : {}) };
  if (activeSession && !headers['X-Access-Token']) headers['X-Access-Token'] = activeSession.token;
  sendJson(res, (routeResult && routeResult.statusCode) || route.statusCode || 200, payload, headers);
  logRequest(req, (routeResult && routeResult.statusCode) || route.statusCode || 200, startedAt);
}

function start() {
  loadConfig();
  watchConfig();
  const portArg = Number(process.argv[2]);
  const port = Number.isFinite(portArg) && portArg > 0 ? portArg : DEFAULT_PORT;
  const server = http.createServer((req, res) => {
    handleRequest(req, res).catch((error) => {
      console.error('[mock] unexpected error:', error);
      sendError(res, 500, 'Internal mock server error');
    });
  });
  server.listen(port, () => {
    console.log(`[mock] server started at http://localhost:${port}`);
    for (const route of mockConfig.routes) console.log(`[mock] ${route.method} ${route.path}`);
  });
}

start();

