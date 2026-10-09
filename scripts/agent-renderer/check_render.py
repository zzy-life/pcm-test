"""本机浏览器检查，不构建 Android；需要 Python Playwright 与已安装的 Edge。"""
import functools
import http.server
from pathlib import Path
import threading
from playwright.sync_api import sync_playwright

assets = Path(__file__).resolve().parents[2] / 'app/src/main/assets/agent-renderer'
server = http.server.ThreadingHTTPServer(('127.0.0.1', 0),
    functools.partial(http.server.SimpleHTTPRequestHandler, directory=str(assets)))
threading.Thread(target=server.serve_forever, daemon=True).start()
try:
    with sync_playwright() as playwright:
        browser = playwright.chromium.launch(channel='msedge', headless=True)
        page = browser.new_page(viewport={'width': 1000, 'height': 500})
        page.goto(f'http://127.0.0.1:{server.server_port}')
        page.wait_for_load_state('networkidle')
        page.wait_for_function('window.agentRenderer && window.agentRenderer.state().ready')

        def update(text, revision, session=1, snapshot=False, reset=False):
            page.evaluate('(value) => window.agentRenderer.update(value)', {
                'text': text, 'revision': revision, 'session': session,
                'snapshot': snapshot, 'reset': reset, 'following': True, 'running': False})
            page.wait_for_function('(r) => window.agentRenderer.state().committed === r', arg=revision)

        update('## 基本情况\n\n<table border="1"><tr>'
               '<td><strong>目前岗位</strong></td><td>岗位等级</td><td>工作年限</td>'
               '<td>专业背景</td><td>年龄</td><td>意向地点</td></tr>'
               '<tr><td>Java后端开发工程师</td><td>初级/中级</td><td>3年</td>'
               '<td>未明确提及</td><td>未明确提及</td><td>未明确提及</td></tr>'
               '<tr><td>目标职位</td><td colspan="5" rowspan="2">高级Java后端开发工程师</td></tr></table>'
               '\n\n<script>window.injected=true</script><img src="https://example.com/leak">'
               '<a href="https://example.com">链接</a>', 1, snapshot=True, reset=True)
        page.wait_for_selector('td[colspan="5"][rowspan="2"]')
        assert page.locator('img,iframe,a').count() == 0
        assert not page.evaluate('Boolean(window.injected)')
        update('这是一个 **测试', 2, session=2, snapshot=True, reset=True)
        update('内容**', 3, session=2)
        assert page.locator('strong').last.inner_text() == '测试内容'
        update('重新开始', 4, session=3, snapshot=True, reset=True)
        assert page.locator('table').count() == 0
        update('\n\n'.join(f'段落 {i}' for i in range(100)), 5,
               session=4, snapshot=True, reset=True)
        page.evaluate('() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)))')
        page.evaluate('window.scrollTo(0, 100)')
        page.wait_for_function('() => !window.agentRenderer.state().following')
        update('\n\n新增段落', 6, session=4)
        assert page.evaluate('window.scrollY') < 200
        page.evaluate('window.agentRenderer.bottom()')
        page.wait_for_function('window.agentRenderer.state().following')
        print('PASS: merged cells, HTML filtering, incremental output, retry reset, scroll following')
        browser.close()
finally:
    server.shutdown()
    server.server_close()
