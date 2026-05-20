import os
import re
import json
import time
import requests
from typing import Optional, List, Dict, Any

class LLMClient:
    """
    大语言模型网络服务调用统一门面 (Facade)
    处理环境发现、安全鉴权、请求组装、响应编解码防雷、以及思维链隔离清洗。
    """
    
    @staticmethod
    def ask(
        messages: List[Dict[str, str]], 
        model_env_key: str = "LLM_MODEL", 
        default_model: str = "qwen2.5:7b",
        temperature: float = 0.3,
        max_tokens: int = 150,
        timeout: float = 30.0,
        enable_thinking: bool = False,
        allow_incomplete_think: bool = False
    ) -> Optional[str]:
        """
        向大语言模型服务发出统一的流转调阅。
        返回值: 经过清洗后的干净纯文本字符串。若请求失败或极端截断则返回 None。
        """
        # 1. 配置探针与参数组装
        llm_url = os.getenv("LLM_API_URL", "http://127.0.0.1:11434/v1/chat/completions")
        llm_model = os.getenv(model_env_key, default_model)
        api_key = os.getenv("LLM_API_KEY", "")

        headers = {"Content-Type": "application/json"}
        # 云原生特性：插入 Bearer 鉴权令牌用于硅基流动等第三方商业服务
        if api_key and api_key.strip():
            headers["Authorization"] = f"Bearer {api_key.strip()}"
            
        payload = {
            "model": llm_model,
            "messages": messages,
            "temperature": temperature,
            "max_tokens": max_tokens
        }
        # [第一性原理加固] 仅在显式启用思维链（enable_thinking 为 True）且配置开关开启时才注入此键值。
        # 避免在关闭思维链（enable_thinking 为 False）时强塞 "enable_thinking": false 导致严格网关抛出 400 错误。
        if enable_thinking and os.getenv("LLM_SEND_ENABLE_THINKING", "false").lower() == "true":
            payload["enable_thinking"] = True
        
        # 2. 网络交互
        try:
            resp = LLMClient._post_with_retry(llm_url, payload, headers, timeout)
        except requests.exceptions.Timeout:
            print(f"⚠️ [LLMClient Timeout] Limit {timeout}s breached for model {llm_model}")
            return None
        except Exception as e:
            print(f"❌ [LLMClient Exception] Network layer failure: {e}")
            return None
            
        # 容灾拦截机制
        if resp.status_code != 200:
            print(f"⚠️ [LLMClient HTTP Error] Status Code: {resp.status_code}, Response: {resp.text[:200]}")
            return None
            
        try:
            # 3. 结果防雷与反序列化
            # 暴力避免因 requests 模块在缺失 header 标识时自行将 payload 强转为 ISO-8859-1 所导致的中文崩坏
            raw_data = json.loads(resp.content.decode('utf-8'))
            content = raw_data.get("choices", [{}])[0].get("message", {}).get("content", "").strip()
        except Exception as e:
            print(f"❌ [LLMClient Exception] JSON/Decoder serialization layer crash: {e}")
            return None

        # 4. 洗碗工机制: 强制剥离 R1 的 <think> 过程
        if '<think>' in content:
            if '</think>' in content:
                content = re.sub(r'<think>.*?</think>', '', content, flags=re.DOTALL).strip()
            else:
                # 非常极端情况：在刚起步“思考”阶段或思考中遇到 max_tokens 制裁或者 Timeout 强杀
                # 没有最终闭环，意味着连真正想输出的一丁点合法内容都没有
                print("⚠️ [LLMClient Cleanup] Incomplete <think> block spotted (likely chopped off by max_tokens or timeout).")
                if allow_incomplete_think:
                    content = re.sub(r'<think>.*$', '', content, flags=re.DOTALL).strip()
                    return content if content else None
                return None
                
        return content if content else None

    @staticmethod
    def _post_with_retry(llm_url: str, payload: Dict[str, Any], headers: Dict[str, str], timeout: float):
        transient = (
            requests.exceptions.SSLError,
            requests.exceptions.ConnectionError,
            requests.exceptions.Timeout,
            requests.exceptions.ChunkedEncodingError,
        )
        last_error = None
        for attempt in range(3):
            try:
                return requests.post(
                    llm_url,
                    json=payload,
                    headers=headers,
                    timeout=LLMClient._timeout_tuple(timeout)
                )
            except transient as exc:
                last_error = exc
                if attempt >= 2:
                    raise
                delay = 0.3 * (2 ** attempt)
                print(f"鈿狅笍 [LLMClient Retry] attempt={attempt + 1} delay={delay:.1f}s error={exc}")
                time.sleep(delay)
        raise last_error

    @staticmethod
    def _timeout_tuple(timeout: float):
        connect_timeout = float(os.getenv("LLM_CONNECT_TIMEOUT", "10"))
        read_timeout = float(os.getenv("LLM_READ_TIMEOUT", str(timeout)))
        return (connect_timeout, max(float(timeout), read_timeout))
