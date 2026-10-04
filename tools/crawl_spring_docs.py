"""
Spring 官方文档爬虫
================================
功能：从 docs.spring.io 抓取 Spring Boot 参考文档，把每个页面转成干净的 .md 文件。

特点：
- 只用 Python 标准库，不需要 pip install 任何东西
- 自动从文档首页发现所有子页面链接（不用你手动整理 URL）
- 只提取正文内容，去掉导航栏、页脚、广告等噪音
- 标题、代码块、段落都会保留，方便后面做 RAG 切分

用法：
    在终端里运行（两个参数都是可选的）：
    python crawl_spring_docs.py                # 用默认配置
    python crawl_spring_docs.py 50             # 只抓前 50 页（先试水用）
    python crawl_spring_docs.py 50 D:/my/dir   # 指定输出目录

友情提示：
- 默认抓 Spring Boot 参考文档。想抓别的模块，改下面的 BASE_URL 和 INDEX_URL。
- 脚本对每个请求间隔 0.5 秒，不给对方服务器添麻烦（这是基本的爬虫礼貌）。
"""

import os
import re
import sys
import time
import html
import urllib.request
import urllib.error
from urllib.parse import urljoin, urlparse

# ============ 配置区（想改就改这里）============

# 文档的入口页（从这里开始发现所有子页面）
INDEX_URL = "https://docs.spring.io/spring-boot/reference/index.html"

# 只抓这个前缀下的页面，避免爬到别的模块或外部站点
ALLOWED_PREFIX = "https://docs.spring.io/spring-boot/reference/"

# 最多抓多少页（防止第一次就抓几千页跑太久）
DEFAULT_MAX_PAGES = 200

# 输出目录（相对当前工作目录）
DEFAULT_OUTPUT_DIR = "spring-docs"

# 每个请求之间的间隔秒数（对目标站点友好，别调太小）
SLEEP_SECONDS = 0.5

# 请求超时（秒）
TIMEOUT = 30

# 伪装成浏览器，避免被简单的反爬规则拦掉
HEADERS = {
    "User-Agent": (
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
        "AppleWebKit/537.36 (KHTML, like Gecko) "
        "Chrome/120.0.0.0 Safari/537.36"
    )
}

# ============ 以下为功能实现 ============


def fetch(url):
    """下载一个页面，返回 HTML 字符串。失败返回 None。"""
    req = urllib.request.Request(url, headers=HEADERS)
    try:
        with urllib.request.urlopen(req, timeout=TIMEOUT) as resp:
            # 文档是 UTF-8 编码
            raw = resp.read()
            return raw.decode("utf-8", errors="replace")
    except urllib.error.HTTPError as e:
        print(f"  [跳过] HTTP {e.code}: {url}")
    except urllib.error.URLError as e:
        print(f"  [跳过] 网络错误 {e.reason}: {url}")
    except Exception as e:
        print(f"  [跳过] 未知错误 {type(e).__name__}: {url}")
    return None


def extract_links(page_html, current_url):
    """从页面里找出所有指向文档内部的链接。"""
    links = set()
    # 找所有 href="..." 的内容
    for match in re.finditer(r'href="([^"#]+)"', page_html):
        href = match.group(1)
        # 忽略图片、样式、脚本等非页面链接
        if not href or href.endswith((".css", ".js", ".png", ".jpg", ".svg", ".ico", ".xml")):
            continue
        # 拼成完整 URL（处理相对路径）
        full = urljoin(current_url, href)
        # 只保留同一文档目录下的 .html 页面
        if full.startswith(ALLOWED_PREFIX) and full.endswith(".html"):
            links.add(full.split("#")[0])
    return links


def html_to_markdown(page_html):
    """
    把页面的 HTML 转成接近 Markdown 的纯文本。
    先锁定 <article class="doc"> 正文区域，再清掉标签。

    说明：这里没用 BeautifulSoup，是为了让你不用装任何第三方库就能跑。
    想升级的话，可以 pip install beautifulsoup4 后换成更精确的解析。
    """
    # 1. 只取正文（Antora 文档站的正文在 <article class="doc"> 里）
    article_match = re.search(
        r'<article[^>]*class="[^"]*doc[^"]*"[^>]*>(.*?)</article>',
        page_html,
        flags=re.DOTALL | re.IGNORECASE,
    )
    content = article_match.group(1) if article_match else page_html

    # 2. 去掉脚本、样式、导航等噪音
    content = re.sub(r"<(script|style|nav|footer|header)[^>]*>.*?</\1>", "", content,
                     flags=re.DOTALL | re.IGNORECASE)

    # 3. 处理代码块（保留代码内容，用 ``` 包裹）
    content = re.sub(
        r'<pre[^>]*>(.*?)</pre>',
        lambda m: "\n```\n" + strip_tags(m.group(1)) + "\n```\n",
        content,
        flags=re.DOTALL | re.IGNORECASE,
    )

    # 4. 各级标题转成 Markdown 标题
    for level in range(1, 7):
        content = re.sub(
            rf"<h{level}[^>]*>(.*?)</h{level}>",
            lambda m, lv=level: "\n\n" + "#" * lv + " " + strip_tags(m.group(1)) + "\n\n",
            content,
            flags=re.DOTALL | re.IGNORECASE,
        )

    # 5. 段落、换行、列表项转换成换行
    content = re.sub(r"</(p|div|li|tr|section)>", "\n", content, flags=re.IGNORECASE)
    content = re.sub(r"<li[^>]*>", "- ", content, flags=re.IGNORECASE)
    content = re.sub(r"<br\s*/?>", "\n", content, flags=re.IGNORECASE)

    # 6. 去掉最后残留的所有标签
    content = strip_tags(content)

    # 7. 解码 HTML 实体（&lt; &gt; &amp; 等）并压缩多余空行
    content = html.unescape(content)
    content = re.sub(r"[ \t]+\n", "\n", content)
    content = re.sub(r"\n{3,}", "\n\n", content)

    return content.strip()


def strip_tags(text):
    """去掉所有 HTML 标签。"""
    return re.sub(r"<[^>]+>", "", text)


def make_filename(url, index):
    """
    根据 URL 生成文件名。
    例如 .../reference/using/index.html -> 002-using-index.md
    """
    path = urlparse(url).path
    # 去掉公共前缀，剩下的部分作为名字
    name = path.replace("/spring-boot/reference/", "").strip("/")
    name = re.sub(r"\.html$", "", name)
    name = name.replace("/", "-") or "index"
    # 只保留安全字符
    name = re.sub(r"[^A-Za-z0-9._-]", "_", name)
    return f"{index:03d}-{name}.md"


def main():
    # 解析命令行参数
    max_pages = int(sys.argv[1]) if len(sys.argv) > 1 else DEFAULT_MAX_PAGES
    output_dir = sys.argv[2] if len(sys.argv) > 2 else DEFAULT_OUTPUT_DIR

    os.makedirs(output_dir, exist_ok=True)
    print(f"输出目录：{os.path.abspath(output_dir)}")
    print(f"最多抓取：{max_pages} 页\n")

    # 待抓队列（用列表模拟，简单直观）
    to_visit = [INDEX_URL]
    visited = set()
    saved = 0

    while to_visit and saved < max_pages:
        url = to_visit.pop(0)
        if url in visited:
            continue
        visited.add(url)

        saved += 1
        print(f"[{saved}/{max_pages}] 抓取中：{url}")

        page = fetch(url)
        if page is None:
            saved -= 1  # 失败的页不占名额
            continue

        # 先收集链接（不管这一页保不保存，链接都要收）
        for link in extract_links(page, url):
            if link not in visited and link not in to_visit:
                to_visit.append(link)

        # 转成 markdown 并保存
        text = html_to_markdown(page)
        if len(text) < 200:
            # 太短的页面一般是索引页，没多少内容，跳过不保存
            print("  （内容过少，跳过保存）")
            saved -= 1
            continue

        filename = make_filename(url, saved)
        filepath = os.path.join(output_dir, filename)
        with open(filepath, "w", encoding="utf-8") as f:
            f.write(f"<!-- 来源: {url} -->\n\n")
            f.write(text)

        time.sleep(SLEEP_SECONDS)

    print(f"\n完成！已保存 {saved} 个文件到：{os.path.abspath(output_dir)}")
    print(f"共发现 {len(visited)} 个页面，队列中还剩余 {len(to_visit)} 个未抓取。")


if __name__ == "__main__":
    main()
