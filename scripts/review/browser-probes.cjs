// Diagnostic probes for the current implementation, not acceptance tests.
// Uses the real frontend; intercepted mutation responses make no ticket changes.
const { chromium } = require('../../frontend/node_modules/@playwright/test');
const assert = require('node:assert/strict');
const base = process.env.BASE_URL || 'http://127.0.0.1:5173';
(async () => {
  const browser = await chromium.launch();
  try {
    const context = await browser.newContext();
    await context.route('http://queue.test/**', async route => {
      if (route.request().url().endsWith('/api/events')) { await route.abort(); return; }
      try {
        const response = await route.fetch({url: route.request().url().replace('http://queue.test', base)});
        await route.fulfill({response});
      } catch (error) { if (!/closed/.test(error.message)) throw error; }
    });
    const page = await context.newPage();
    await page.goto('http://queue.test/');
    await page.getByRole('button', {name:'Join the queue'}).click();
    await page.getByRole('alert').waitFor();
    const insecure = await page.evaluate(() => ({secureContext:isSecureContext,randomUUID:typeof crypto.randomUUID}));
    const message = await page.getByRole('alert').innerText();
    assert.equal(insecure.secureContext, false);
    assert.match(message, /randomUUID/);
    console.log(JSON.stringify({probe:'HTTP LAN origin',...insecure,error:message}));
    await context.close();

    const retryContext = await browser.newContext();
    const retryPage = await retryContext.newPage();
    const keys=[];
    await retryPage.route('**/api/student/join',async route => {
      keys.push(route.request().headers()['idempotency-key']);
      if(keys.length===1) await route.abort('failed');
      else await route.fulfill({status:keys.length===2?429:400,contentType:'application/json',body:JSON.stringify({message:'Diagnostic response'})});
    });
    await retryPage.goto(base);
    for(let i=0;i<3;i++) {
      await retryPage.getByRole('button',{name:'Join the queue'}).click();
      await retryPage.getByRole('alert').waitFor();
      await retryPage.getByRole('button',{name:'Join the queue'}).waitFor();
    }
    assert.equal(keys.length,3);assert.equal(keys[0],keys[1]);assert.notEqual(keys[1],keys[2]);
    console.log(JSON.stringify({probe:'network failure then 429',sameKeyOnFirstRetry:keys[0]===keys[1],sameKeyAfter429:keys[1]===keys[2]}));
    await retryContext.close();
  } finally {await browser.close();}
})().catch(error=>{console.error(error.message);process.exitCode=1;});
