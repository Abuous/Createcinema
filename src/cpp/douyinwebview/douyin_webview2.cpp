#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#define UNICODE
#define _UNICODE
#include <windows.h>
#include <jni.h>
#include <wrl.h>
#include <WebView2.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstdint>
#include <functional>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <utility>
#include <vector>

using Microsoft::WRL::Callback;
using Microsoft::WRL::ComPtr;

namespace {

constexpr UINT WM_RUN_TASK = WM_APP + 1;
constexpr UINT WM_SHUTDOWN = WM_APP + 2;
constexpr size_t MAX_RESPONSE_BYTES = 64ULL * 1024ULL * 1024ULL;

std::string utf8(const std::wstring& value) {
    if (value.empty()) return {};
    int size = WideCharToMultiByte(CP_UTF8, 0, value.data(), static_cast<int>(value.size()),
                                   nullptr, 0, nullptr, nullptr);
    if (size <= 0) return {};
    std::string result(static_cast<size_t>(size), '\0');
    WideCharToMultiByte(CP_UTF8, 0, value.data(), static_cast<int>(value.size()),
                        result.data(), size, nullptr, nullptr);
    return result;
}

std::wstring wide(JNIEnv* env, jstring value) {
    if (value == nullptr) return {};
    const jchar* chars = env->GetStringChars(value, nullptr);
    if (chars == nullptr) return {};
    jsize length = env->GetStringLength(value);
    std::wstring result(reinterpret_cast<const wchar_t*>(chars), static_cast<size_t>(length));
    env->ReleaseStringChars(value, chars);
    return result;
}

std::string hresultMessage(HRESULT result) {
    wchar_t* buffer = nullptr;
    DWORD length = FormatMessageW(FORMAT_MESSAGE_ALLOCATE_BUFFER | FORMAT_MESSAGE_FROM_SYSTEM |
                                          FORMAT_MESSAGE_IGNORE_INSERTS,
                                  nullptr, static_cast<DWORD>(result), 0,
                                  reinterpret_cast<wchar_t*>(&buffer), 0, nullptr);
    std::wstring text;
    if (length > 0 && buffer != nullptr) {
        text.assign(buffer, length);
        while (!text.empty() && (text.back() == L'\r' || text.back() == L'\n' || text.back() == L' ')) {
            text.pop_back();
        }
        LocalFree(buffer);
    }
    char code[16];
    sprintf_s(code, "0x%08X", static_cast<unsigned int>(result));
    return text.empty() ? code : utf8(text) + " (" + code + ")";
}

void throwIOException(JNIEnv* env, const std::string& message) {
    jclass type = env->FindClass("java/io/IOException");
    if (type != nullptr) env->ThrowNew(type, message.c_str());
}

bool equalsIgnoreCase(const std::wstring& left, const std::wstring& right) {
    return left.size() == right.size() &&
           _wcsnicmp(left.c_str(), right.c_str(), left.size()) == 0;
}

struct ParsedUri {
    std::wstring host;
    std::wstring path;
    std::wstring query;
};

bool parseHttpsUri(const std::wstring& value, ParsedUri& result) {
    constexpr wchar_t HTTPS[] = L"https://";
    if (value.size() < 8 || _wcsnicmp(value.c_str(), HTTPS, 8) != 0) return false;
    size_t authorityStart = 8;
    size_t authorityEnd = value.find_first_of(L"/?#", authorityStart);
    std::wstring authority = value.substr(authorityStart, authorityEnd - authorityStart);
    if (authority.empty() || authority.find(L'@') != std::wstring::npos) return false;
    size_t colon = authority.rfind(L':');
    if (colon != std::wstring::npos) {
        std::wstring port = authority.substr(colon + 1);
        if (port != L"443") return false;
        authority.resize(colon);
    }
    if (authority.empty()) return false;

    size_t pathStart = authorityEnd;
    size_t queryStart = value.find(L'?', authorityEnd);
    size_t fragmentStart = value.find(L'#', authorityEnd);
    size_t pathEnd = std::min(queryStart == std::wstring::npos ? value.size() : queryStart,
                              fragmentStart == std::wstring::npos ? value.size() : fragmentStart);
    result.host = std::move(authority);
    result.path = pathStart != std::wstring::npos && pathStart < value.size() && value[pathStart] == L'/'
                          ? value.substr(pathStart, pathEnd - pathStart)
                          : L"/";
    if (queryStart != std::wstring::npos &&
        (fragmentStart == std::wstring::npos || queryStart < fragmentStart)) {
        size_t end = fragmentStart == std::wstring::npos ? value.size() : fragmentStart;
        result.query = value.substr(queryStart + 1, end - queryStart - 1);
    } else {
        result.query.clear();
    }
    return true;
}

bool queryMatches(const std::wstring& query, const std::wstring& name, const std::wstring& value) {
    if (name.empty()) return true;
    size_t start = 0;
    while (start <= query.size()) {
        size_t end = query.find(L'&', start);
        if (end == std::wstring::npos) end = query.size();
        size_t equals = query.find(L'=', start);
        if (equals != std::wstring::npos && equals < end &&
            query.substr(start, equals - start) == name && query.substr(equals + 1, end - equals - 1) == value) {
            return true;
        }
        if (end == query.size()) break;
        start = end + 1;
    }
    return false;
}

struct Operation {
    std::mutex mutex;
    std::condition_variable condition;
    bool done = false;
    bool cancelled = false;
    bool unavailable = false;
    std::string result;
    std::string error;

    void complete(std::string body) {
        std::lock_guard lock(mutex);
        if (done || cancelled) return;
        result = std::move(body);
        done = true;
        condition.notify_all();
    }

    void fail(std::string message, bool captureUnavailable = false) {
        std::lock_guard lock(mutex);
        if (done || cancelled) return;
        error = std::move(message);
        unavailable = captureUnavailable;
        done = true;
        condition.notify_all();
    }
};

struct CaptureSpec {
    uint64_t id;
    std::wstring host;
    std::vector<std::wstring> paths;
    std::wstring queryName;
    std::wstring queryValue;
    std::shared_ptr<Operation> operation;
};

class WebViewEngine : public std::enable_shared_from_this<WebViewEngine> {
public:
    ~WebViewEngine() { shutdown(); }

    bool initialize(std::wstring profile, std::string& error) {
        std::unique_lock lock(stateMutex_);
        if (thread_.joinable()) {
            if (ready_) return true;
            error = startupError_.empty() ? "WebView2 initialization failed" : startupError_;
            return false;
        }
        startupComplete_ = false;
        ready_ = false;
        thread_ = std::thread([self = shared_from_this(), profile = std::move(profile)]() mutable {
            self->threadMain(std::move(profile));
        });
        if (!stateCondition_.wait_for(lock, std::chrono::seconds(25), [this] { return startupComplete_; })) {
            error = "WebView2 initialization timed out";
            lock.unlock();
            shutdown();
            return false;
        }
        if (!ready_) {
            error = startupError_.empty() ? "WebView2 initialization failed" : startupError_;
            lock.unlock();
            shutdown();
            return false;
        }
        return true;
    }

    bool showLogin(const std::wstring& url, std::string& error) {
        auto operation = std::make_shared<Operation>();
        if (!submit([this, operation, url] {
                if (!webview_ || !controller_ || !window_) {
                    operation->fail("WebView2 is not ready");
                    return;
                }
                RECT desktop{};
                SystemParametersInfoW(SPI_GETWORKAREA, 0, &desktop, 0);
                constexpr int width = 1100;
                constexpr int height = 760;
                int x = desktop.left + std::max(0L, (desktop.right - desktop.left - width) / 2);
                int y = desktop.top + std::max(0L, (desktop.bottom - desktop.top - height) / 2);
                SetWindowPos(window_, HWND_TOP, x, y, width, height, SWP_SHOWWINDOW);
                RECT bounds{0, 0, width, height};
                controller_->put_Bounds(bounds);
                controller_->put_IsVisible(TRUE);
                ShowWindow(window_, SW_RESTORE);
                SetForegroundWindow(window_);
                authorized_.store(false);
                HRESULT hr = webview_->Navigate(url.c_str());
                if (FAILED(hr)) operation->fail("Could not navigate the WebView2 login page: " + hresultMessage(hr));
                else operation->complete({});
            }, error)) return false;
        return waitOperation(operation, std::chrono::seconds(10), error, nullptr);
    }

    bool capture(const std::wstring& url, const std::wstring& host,
                 std::vector<std::wstring> paths, const std::wstring& queryName,
                 const std::wstring& queryValue, std::chrono::milliseconds timeout,
                 std::string& body, std::string& error, bool& unavailable) {
        auto operation = std::make_shared<Operation>();
        uint64_t id = nextCapture_.fetch_add(1) + 1;
        if (!submit([this, operation, id, url, host, paths = std::move(paths), queryName, queryValue]() mutable {
                if (!webview_ || !controller_) {
                    operation->fail("WebView2 is not ready");
                    return;
                }
                activeCapture_ = std::make_shared<CaptureSpec>(CaptureSpec{
                        id, host, std::move(paths), queryName, queryValue, operation});
                controller_->put_IsVisible(FALSE);
                if (window_) ShowWindow(window_, SW_HIDE);
                HRESULT hr = webview_->Navigate(url.c_str());
                if (FAILED(hr)) {
                    activeCapture_.reset();
                    operation->fail("Could not navigate WebView2: " + hresultMessage(hr));
                }
            }, error)) return false;

        std::unique_lock lock(operation->mutex);
        if (!operation->condition.wait_for(lock, timeout, [&operation] { return operation->done; })) {
            operation->cancelled = true;
            unavailable = true;
            error = "WebView2 did not capture the requested Douyin response before timeout";
            submit([this, id] {
                if (activeCapture_ && activeCapture_->id == id) activeCapture_.reset();
            }, error);
            return false;
        }
        body = std::move(operation->result);
        error = std::move(operation->error);
        unavailable = operation->unavailable;
        return error.empty();
    }

    void hide() {
        std::string ignored;
        submit([this] {
            if (controller_) controller_->put_IsVisible(FALSE);
            if (window_) ShowWindow(window_, SW_HIDE);
        }, ignored);
    }

    bool authorized() const {
        return authorized_.load();
    }

    void shutdown() {
        std::thread thread;
        HWND window = nullptr;
        {
            std::lock_guard lock(stateMutex_);
            if (!thread_.joinable()) return;
            window = window_;
            thread = std::move(thread_);
        }
        if (window) PostMessageW(window, WM_SHUTDOWN, 0, 0);
        else if (uiThreadId_ != 0) PostThreadMessageW(uiThreadId_, WM_QUIT, 0, 0);
        if (thread.joinable() && thread.get_id() != std::this_thread::get_id()) thread.join();
    }

private:
    static LRESULT CALLBACK windowProc(HWND window, UINT message, WPARAM wParam, LPARAM lParam) {
        WebViewEngine* self = reinterpret_cast<WebViewEngine*>(GetWindowLongPtrW(window, GWLP_USERDATA));
        if (message == WM_NCCREATE) {
            auto create = reinterpret_cast<CREATESTRUCTW*>(lParam);
            self = static_cast<WebViewEngine*>(create->lpCreateParams);
            SetWindowLongPtrW(window, GWLP_USERDATA, reinterpret_cast<LONG_PTR>(self));
        }
        if (self) {
            if (message == WM_RUN_TASK) {
                self->runPendingTask();
                return 0;
            }
            if (message == WM_SHUTDOWN) {
                DestroyWindow(window);
                return 0;
            }
            if (message == WM_SIZE && self->controller_) {
                RECT bounds{};
                GetClientRect(window, &bounds);
                self->controller_->put_Bounds(bounds);
            }
        }
        if (message == WM_DESTROY) {
            PostQuitMessage(0);
            return 0;
        }
        return DefWindowProcW(window, message, wParam, lParam);
    }

    void threadMain(std::wstring profile) {
        uiThreadId_ = GetCurrentThreadId();
        HRESULT com = CoInitializeEx(nullptr, COINIT_APARTMENTTHREADED);
        if (FAILED(com)) {
            signalStartupFailure("Could not initialize COM for WebView2: " + hresultMessage(com));
            return;
        }

        WNDCLASSEXW windowClass{};
        windowClass.cbSize = sizeof(windowClass);
        windowClass.lpfnWndProc = windowProc;
        windowClass.hInstance = GetModuleHandleW(nullptr);
        windowClass.hCursor = LoadCursorW(nullptr, IDC_ARROW);
        windowClass.hbrBackground = reinterpret_cast<HBRUSH>(COLOR_WINDOW + 1);
        windowClass.lpszClassName = L"CreateCinemaDouyinWebView2";
        RegisterClassExW(&windowClass);
        window_ = CreateWindowExW(0, windowClass.lpszClassName, L"CreateCinema - Douyin authorization",
                                  WS_OVERLAPPEDWINDOW, CW_USEDEFAULT, CW_USEDEFAULT, 1100, 760,
                                  nullptr, nullptr, windowClass.hInstance, this);
        if (!window_) {
            signalStartupFailure("Could not create the WebView2 host window");
            CoUninitialize();
            return;
        }
        ShowWindow(window_, SW_HIDE);

        HRESULT started = CreateCoreWebView2EnvironmentWithOptions(
                nullptr, profile.c_str(), nullptr,
                Callback<ICoreWebView2CreateCoreWebView2EnvironmentCompletedHandler>(
                        [this](HRESULT result, ICoreWebView2Environment* environment) -> HRESULT {
                            if (FAILED(result) || environment == nullptr) {
                                signalStartupFailure("Could not create the WebView2 environment: " +
                                                     hresultMessage(result));
                                return S_OK;
                            }
                            environment_ = environment;
                            HRESULT controllerResult = environment_->CreateCoreWebView2Controller(
                                    window_, Callback<ICoreWebView2CreateCoreWebView2ControllerCompletedHandler>(
                                                     [this](HRESULT result,
                                                            ICoreWebView2Controller* controller) -> HRESULT {
                                                         if (FAILED(result) || controller == nullptr) {
                                                             signalStartupFailure(
                                                                     "Could not create the WebView2 controller: " +
                                                                     hresultMessage(result));
                                                             return S_OK;
                                                         }
                                                         controller_ = controller;
                                                         HRESULT coreResult = controller_->get_CoreWebView2(&webview_);
                                                         if (FAILED(coreResult) || !webview_) {
                                                             signalStartupFailure(
                                                                     "Could not obtain the WebView2 core: " +
                                                                     hresultMessage(coreResult));
                                                             return S_OK;
                                                         }
                                                         RECT bounds{};
                                                         GetClientRect(window_, &bounds);
                                                         controller_->put_Bounds(bounds);
                                                         controller_->put_IsVisible(FALSE);
                                                         HRESULT interfaceResult = webview_.As(&webview2_);
                                                          if (FAILED(interfaceResult) || !webview2_) {
                                                              signalStartupFailure(
                                                                      "The installed WebView2 Runtime is too old: " +
                                                                      hresultMessage(interfaceResult));
                                                              return S_OK;
                                                          }
                                                          ComPtr<ICoreWebView2_8> webview8;
                                                          HRESULT muteInterfaceResult = webview_.As(&webview8);
                                                          if (FAILED(muteInterfaceResult) || !webview8) {
                                                              signalStartupFailure(
                                                                      "The installed WebView2 Runtime cannot mute browser audio: " +
                                                                      hresultMessage(muteInterfaceResult));
                                                              return S_OK;
                                                          }
                                                          HRESULT muteResult = webview8->put_IsMuted(TRUE);
                                                          if (FAILED(muteResult)) {
                                                              signalStartupFailure("Could not mute WebView2 audio: " +
                                                                                   hresultMessage(muteResult));
                                                              return S_OK;
                                                          }
                                                          EventRegistrationToken token{};
                                                         HRESULT eventResult = webview2_->add_WebResourceResponseReceived(
                                                                 Callback<ICoreWebView2WebResourceResponseReceivedEventHandler>(
                                                                         [this](ICoreWebView2*,
                                                                                ICoreWebView2WebResourceResponseReceivedEventArgs*
                                                                                        args) -> HRESULT {
                                                                             handleResponse(args);
                                                                             return S_OK;
                                                                         })
                                                                         .Get(),
                                                                 &token);
                                                         if (FAILED(eventResult)) {
                                                             signalStartupFailure(
                                                                     "Could not subscribe to WebView2 responses: " +
                                                                     hresultMessage(eventResult));
                                                             return S_OK;
                                                         }
                                                         responseToken_ = token;
                                                         signalStartupReady();
                                                         return S_OK;
                                                     })
                                                     .Get());
                            if (FAILED(controllerResult)) {
                                signalStartupFailure("Could not start the WebView2 controller: " +
                                                     hresultMessage(controllerResult));
                            }
                            return S_OK;
                        })
                        .Get());
        if (FAILED(started)) signalStartupFailure("Could not start WebView2: " + hresultMessage(started));

        MSG message{};
        while (GetMessageW(&message, nullptr, 0, 0) > 0) {
            TranslateMessage(&message);
            DispatchMessageW(&message);
        }

        if (webview2_) webview2_->remove_WebResourceResponseReceived(responseToken_);
        if (activeCapture_) {
            activeCapture_->operation->fail("WebView2 was closed during capture");
        }
        activeCapture_.reset();
        webview2_.Reset();
        webview_.Reset();
        controller_.Reset();
        environment_.Reset();
        {
            std::lock_guard lock(stateMutex_);
            window_ = nullptr;
            ready_ = false;
            uiThreadId_ = 0;
        }
        CoUninitialize();
    }

    void signalStartupReady() {
        std::lock_guard lock(stateMutex_);
        ready_ = true;
        startupComplete_ = true;
        stateCondition_.notify_all();
    }

    void signalStartupFailure(std::string error) {
        std::lock_guard lock(stateMutex_);
        if (startupComplete_ && ready_) return;
        startupError_ = std::move(error);
        ready_ = false;
        startupComplete_ = true;
        stateCondition_.notify_all();
    }

    bool submit(std::function<void()> task, std::string& error) {
        HWND window;
        {
            std::lock_guard lock(stateMutex_);
            if (!ready_ || !window_) {
                error = startupError_.empty() ? "WebView2 is not ready" : startupError_;
                return false;
            }
            window = window_;
        }
        {
            std::lock_guard lock(taskMutex_);
            pendingTasks_.push_back(std::move(task));
        }
        if (!PostMessageW(window, WM_RUN_TASK, 0, 0)) {
            error = "Could not dispatch a WebView2 command";
            return false;
        }
        return true;
    }

    void runPendingTask() {
        std::vector<std::function<void()>> tasks;
        {
            std::lock_guard lock(taskMutex_);
            tasks.swap(pendingTasks_);
        }
        for (auto& task : tasks) task();
    }

    bool waitOperation(const std::shared_ptr<Operation>& operation, std::chrono::milliseconds timeout,
                       std::string& error, bool* unavailable) {
        std::unique_lock lock(operation->mutex);
        if (!operation->condition.wait_for(lock, timeout, [&operation] { return operation->done; })) {
            operation->cancelled = true;
            error = "WebView2 command timed out";
            if (unavailable) *unavailable = false;
            return false;
        }
        error = operation->error;
        if (unavailable) *unavailable = operation->unavailable;
        return error.empty();
    }

    void handleResponse(ICoreWebView2WebResourceResponseReceivedEventArgs* args) {
        auto capture = activeCapture_;
        if (!args) return;

        ComPtr<ICoreWebView2WebResourceRequest> request;
        if (FAILED(args->get_Request(&request)) || !request) return;
        wchar_t* rawUri = nullptr;
        if (FAILED(request->get_Uri(&rawUri)) || !rawUri) return;
        std::wstring uri(rawUri);
        CoTaskMemFree(rawUri);

        ParsedUri parsed;
        if (!parseHttpsUri(uri, parsed)) return;
        if (!capture || capture->operation->cancelled) {
            refreshLoginCookies();
            return;
        }
        if (!equalsIgnoreCase(parsed.host, capture->host)) return;
        bool pathMatches = std::any_of(capture->paths.begin(), capture->paths.end(),
                                       [&parsed](const std::wstring& path) {
                                           return parsed.path.size() >= path.size() &&
                                                  parsed.path.compare(0, path.size(), path) == 0;
                                       });
        if (!pathMatches || !queryMatches(parsed.query, capture->queryName, capture->queryValue)) return;

        ComPtr<ICoreWebView2WebResourceResponseView> response;
        if (FAILED(args->get_Response(&response)) || !response) {
            completeCaptureFailure(capture, "CAPTURE_UNAVAILABLE: WebView2 response was unavailable", true);
            return;
        }
        int status = 0;
        if (FAILED(response->get_StatusCode(&status)) || status < 200 || status >= 300) {
            completeCaptureFailure(capture,
                                   "CAPTURE_UNAVAILABLE: Douyin returned HTTP " + std::to_string(status), true);
            return;
        }

        HRESULT contentResult = response->GetContent(
                Callback<ICoreWebView2WebResourceResponseViewGetContentCompletedHandler>(
                        [this, capture](HRESULT result, IStream* stream) -> HRESULT {
                            if (!activeCapture_ || activeCapture_->id != capture->id ||
                                capture->operation->cancelled) return S_OK;
                            if (FAILED(result) || stream == nullptr) {
                                return S_OK;
                            }
                            std::string body;
                            char buffer[16 * 1024];
                            while (true) {
                                ULONG read = 0;
                                HRESULT readResult = stream->Read(buffer, sizeof(buffer), &read);
                                if (FAILED(readResult)) {
                                    completeCaptureFailure(capture, "Could not read the WebView2 response body", false);
                                    return S_OK;
                                }
                                if (read == 0) break;
                                if (body.size() + read > MAX_RESPONSE_BYTES) {
                                    completeCaptureFailure(capture, "WebView2 response body was too large", false);
                                    return S_OK;
                                }
                                body.append(buffer, read);
                            }
                            if (body.empty()) {
                                return S_OK;
                            }
                            authorized_.store(true);
                            if (activeCapture_ && activeCapture_->id == capture->id) activeCapture_.reset();
                            capture->operation->complete(std::move(body));
                            return S_OK;
                        })
                        .Get());
        if (FAILED(contentResult)) {
            return;
        }
    }

    void completeCaptureFailure(const std::shared_ptr<CaptureSpec>& capture, std::string error,
                                bool unavailable) {
        if (activeCapture_ && activeCapture_->id == capture->id) activeCapture_.reset();
        capture->operation->fail(std::move(error), unavailable);
    }

    void refreshLoginCookies() {
        if (!webview2_ || cookieCheckPending_.exchange(true)) return;
        ComPtr<ICoreWebView2CookieManager> manager;
        if (FAILED(webview2_->get_CookieManager(&manager)) || !manager) {
            cookieCheckPending_.store(false);
            return;
        }
        HRESULT result = manager->GetCookies(
                L"https://www.douyin.com",
                Callback<ICoreWebView2GetCookiesCompletedHandler>(
                        [this](HRESULT result, ICoreWebView2CookieList* cookies) -> HRESULT {
                            bool loggedIn = false;
                            UINT32 count = 0;
                            if (SUCCEEDED(result) && cookies && SUCCEEDED(cookies->get_Count(&count))) {
                                for (UINT32 index = 0; index < count && !loggedIn; index++) {
                                    ComPtr<ICoreWebView2Cookie> cookie;
                                    if (FAILED(cookies->GetValueAtIndex(index, &cookie)) || !cookie) continue;
                                    wchar_t* rawName = nullptr;
                                    if (FAILED(cookie->get_Name(&rawName)) || !rawName) continue;
                                    std::wstring name(rawName);
                                    CoTaskMemFree(rawName);
                                    loggedIn = equalsIgnoreCase(name, L"sessionid") ||
                                               equalsIgnoreCase(name, L"sessionid_ss") ||
                                               equalsIgnoreCase(name, L"sid_tt");
                                }
                            }
                            authorized_.store(loggedIn);
                            cookieCheckPending_.store(false);
                            return S_OK;
                        })
                        .Get());
        if (FAILED(result)) cookieCheckPending_.store(false);
    }

    std::mutex stateMutex_;
    std::condition_variable stateCondition_;
    std::thread thread_;
    DWORD uiThreadId_ = 0;
    HWND window_ = nullptr;
    bool startupComplete_ = false;
    bool ready_ = false;
    std::string startupError_;

    std::mutex taskMutex_;
    std::vector<std::function<void()>> pendingTasks_;
    ComPtr<ICoreWebView2Environment> environment_;
    ComPtr<ICoreWebView2Controller> controller_;
    ComPtr<ICoreWebView2> webview_;
    ComPtr<ICoreWebView2_2> webview2_;
    EventRegistrationToken responseToken_{};
    std::shared_ptr<CaptureSpec> activeCapture_;
    std::atomic<uint64_t> nextCapture_{0};
    std::atomic<bool> authorized_{false};
    std::atomic<bool> cookieCheckPending_{false};
};

std::mutex engineMutex;
std::shared_ptr<WebViewEngine> engine;

std::shared_ptr<WebViewEngine> currentEngine() {
    std::lock_guard lock(engineMutex);
    return engine;
}

} // namespace

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_yfy_createcinema_client_DouyinWebView2Native_isRuntimeAvailable0(JNIEnv*, jclass) {
    wchar_t* version = nullptr;
    HRESULT result = GetAvailableCoreWebView2BrowserVersionString(nullptr, &version);
    if (version) CoTaskMemFree(version);
    return SUCCEEDED(result) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_yfy_createcinema_client_DouyinWebView2Native_initialize0(JNIEnv* env, jclass, jstring profile) {
    std::wstring profilePath = wide(env, profile);
    if (profilePath.empty()) {
        throwIOException(env, "WebView2 profile path is empty");
        return;
    }
    std::shared_ptr<WebViewEngine> instance;
    {
        std::lock_guard lock(engineMutex);
        if (!engine) engine = std::make_shared<WebViewEngine>();
        instance = engine;
    }
    std::string error;
    if (!instance->initialize(std::move(profilePath), error)) throwIOException(env, error);
}

JNIEXPORT void JNICALL
Java_com_yfy_createcinema_client_DouyinWebView2Native_showLogin0(JNIEnv* env, jclass, jstring url) {
    auto instance = currentEngine();
    if (!instance) {
        throwIOException(env, "WebView2 is not initialized");
        return;
    }
    std::string error;
    if (!instance->showLogin(wide(env, url), error)) throwIOException(env, error);
}

JNIEXPORT jbyteArray JNICALL
Java_com_yfy_createcinema_client_DouyinWebView2Native_capture0(
        JNIEnv* env, jclass, jstring navigationUrl, jstring expectedHost, jobjectArray expectedPaths,
        jstring queryName, jstring queryValue, jint timeoutMillis) {
    auto instance = currentEngine();
    if (!instance) {
        throwIOException(env, "WebView2 is not initialized");
        return nullptr;
    }
    std::vector<std::wstring> paths;
    if (expectedPaths != nullptr) {
        jsize count = env->GetArrayLength(expectedPaths);
        paths.reserve(static_cast<size_t>(count));
        for (jsize index = 0; index < count; index++) {
            auto value = static_cast<jstring>(env->GetObjectArrayElement(expectedPaths, index));
            paths.push_back(wide(env, value));
            env->DeleteLocalRef(value);
        }
    }
    if (paths.empty()) {
        throwIOException(env, "WebView2 capture has no expected response path");
        return nullptr;
    }

    std::string body;
    std::string error;
    bool unavailable = false;
    auto timeout = std::chrono::milliseconds(std::max(1000, static_cast<int>(timeoutMillis)));
    if (!instance->capture(wide(env, navigationUrl), wide(env, expectedHost), std::move(paths),
                           wide(env, queryName), wide(env, queryValue), timeout, body, error, unavailable)) {
        throwIOException(env, unavailable && error.rfind("CAPTURE_UNAVAILABLE:", 0) != 0
                                      ? "CAPTURE_UNAVAILABLE: " + error
                                      : error);
        return nullptr;
    }
    jbyteArray result = env->NewByteArray(static_cast<jsize>(body.size()));
    if (result == nullptr) return nullptr;
    env->SetByteArrayRegion(result, 0, static_cast<jsize>(body.size()),
                            reinterpret_cast<const jbyte*>(body.data()));
    return result;
}

JNIEXPORT void JNICALL
Java_com_yfy_createcinema_client_DouyinWebView2Native_hide0(JNIEnv*, jclass) {
    auto instance = currentEngine();
    if (instance) instance->hide();
}

JNIEXPORT jboolean JNICALL
Java_com_yfy_createcinema_client_DouyinWebView2Native_isAuthorized0(JNIEnv*, jclass) {
    auto instance = currentEngine();
    return instance && instance->authorized() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_yfy_createcinema_client_DouyinWebView2Native_shutdown0(JNIEnv*, jclass) {
    std::shared_ptr<WebViewEngine> instance;
    {
        std::lock_guard lock(engineMutex);
        instance = std::move(engine);
    }
    if (instance) instance->shutdown();
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM*, void*) {
    return JNI_VERSION_1_8;
}

} // extern "C"
