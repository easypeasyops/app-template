use std::sync::Arc;

use app::{Metrics, log, metrics_router, router, serve};
use axum::Router;
use axum::body::Body;
use axum::http::{Request, StatusCode};
use http_body_util::BodyExt;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::{TcpListener, TcpStream};
use tower::ServiceExt;

async fn get(router: &Router, path: &str) -> (StatusCode, String) {
    let request = Request::get(path).body(Body::empty()).unwrap();
    let response = router.clone().oneshot(request).await.unwrap();
    let status = response.status();
    let body = response.into_body().collect().await.unwrap().to_bytes();
    (status, String::from_utf8(body.to_vec()).unwrap())
}

#[tokio::test]
async fn hello_answers_json() {
    let (status, body) = get(&router(Arc::default()), "/").await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(body, r#"{"hello":"world"}"#);
}

#[tokio::test]
async fn probes_answer_ok() {
    let router = router(Arc::default());
    assert_eq!(
        get(&router, "/healthz").await,
        (StatusCode::OK, "ok".into())
    );
    assert_eq!(get(&router, "/readyz").await, (StatusCode::OK, "ok".into()));
}

/// The names and labels the platform's SLO rules select on: by route, never by raw path.
#[tokio::test]
async fn metrics_count_requests_by_route() {
    let metrics = Arc::new(Metrics::default());
    let router = router(metrics.clone());
    get(&router, "/").await;
    get(&router, "/").await;
    assert_eq!(get(&router, "/no/such/path").await.0, StatusCode::NOT_FOUND);

    let (status, text) = get(&metrics_router(metrics), "/metrics").await;
    assert_eq!(status, StatusCode::OK);
    assert!(text.contains(r#"http_requests_total{method="GET",route="/",status="200"} 2"#));
    assert!(text.contains(r#"http_requests_total{method="GET",route="unmatched",status="404"} 1"#));
    assert!(
        text.contains(r#"http_request_duration_seconds_bucket{le="0.5",method="GET",route="/"} 2"#)
    );
    assert!(!text.contains("/no/such/path"));
}

async fn http_get(port: u16, path: &str) -> String {
    let mut stream = TcpStream::connect(("127.0.0.1", port)).await.unwrap();
    let request = format!("GET {path} HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
    stream.write_all(request.as_bytes()).await.unwrap();
    let mut response = String::new();
    stream.read_to_string(&mut response).await.unwrap();
    response
}

#[tokio::test]
async fn serves_both_ports_until_shutdown() {
    let app_listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let metrics_listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let (app_port, metrics_port) = (
        app_listener.local_addr().unwrap().port(),
        metrics_listener.local_addr().unwrap().port(),
    );
    let (stop, stopped) = tokio::sync::oneshot::channel::<()>();
    let server = tokio::spawn(serve(app_listener, metrics_listener, async {
        let _ = stopped.await;
    }));

    assert!(
        http_get(app_port, "/readyz")
            .await
            .starts_with("HTTP/1.1 200")
    );
    let metrics = http_get(metrics_port, "/metrics").await;
    assert!(
        metrics.contains(r#"http_requests_total{method="GET",route="/readyz",status="200"} 1"#)
    );

    stop.send(()).unwrap();
    server.await.unwrap().unwrap();
}

async fn get_with_id(router: &Router, id: Option<&str>) -> Option<String> {
    let mut request = Request::get("/");
    if let Some(id) = id {
        request = request.header("X-Request-ID", id);
    }
    let response = router
        .clone()
        .oneshot(request.body(Body::empty()).unwrap())
        .await
        .unwrap();
    let id = response.headers().get("x-request-id")?;
    Some(id.to_str().unwrap().to_owned())
}

#[tokio::test]
async fn a_request_id_is_sent_back_kept_from_the_caller_or_made() {
    let router = router(Arc::default());
    assert_eq!(
        get_with_id(&router, Some("abc-123")).await.as_deref(),
        Some("abc-123")
    );
    let made = get_with_id(&router, None).await.unwrap();
    assert!(made.len() == 32 && made.bytes().all(|b| b.is_ascii_hexdigit()));
    let replaced = get_with_id(&router, Some("bad id!")).await.unwrap();
    assert_ne!(replaced, "bad id!");
    assert_eq!(replaced.len(), 32);
}

#[tokio::test]
async fn log_lines_are_json_with_the_request_id_while_it_is_served() {
    let mut fields = serde_json::Map::new();
    fields.insert("status".into(), 200.into());
    let line = app::log::REQUEST_ID
        .scope("trace-me".into(), async {
            log::line("info", "app.access", "request", fields)
        })
        .await;
    let entry: serde_json::Value = serde_json::from_str(&line).unwrap();
    assert_eq!(entry["request_id"], "trace-me");
    assert_eq!(entry["msg"], "request");
    assert_eq!(entry["level"], "info");
    assert_eq!(entry["logger"], "app.access");
    assert_eq!(entry["status"], 200);
    assert!(entry["ts"].as_str().unwrap().ends_with('Z'));

    let outside: serde_json::Value =
        serde_json::from_str(&log::line("info", "app", "started", serde_json::Map::new())).unwrap();
    assert!(outside.get("request_id").is_none());
}
