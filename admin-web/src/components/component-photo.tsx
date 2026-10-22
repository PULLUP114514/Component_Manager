import { useEffect, useState } from "react";

import { Button } from "@/components/ui/button";
import { useAuth } from "@/hooks/use-auth";
import { normalizeBaseUrl } from "@/lib/api";
import { useI18n } from "@/lib/i18n";

/** Private photos use the existing account session; credentials never enter an image URL. */
export function ComponentPhoto({ photoKey, name, compact = false }: {
  photoKey?: string | null; name: string; compact?: boolean;
}) {
  const { session, identity } = useAuth();
  const { t } = useI18n();
  const [attempt, setAttempt] = useState(0);
  const [photo, setPhoto] = useState<{ key: string; url: string } | null>(null);
  const [error, setError] = useState<"missing" | "session" | "failed" | null>(null);
  const baseUrl = session?.apiBaseUrl;
  const accountId = identity?.account_id;

  useEffect(() => {
    setPhoto(null);
    setError(null);
    if (!baseUrl || !photoKey) return;
    let active = true;
    let objectUrl: string | undefined;
    const controller = new AbortController();
    const timeout = window.setTimeout(() => controller.abort(), 15_000);
    void (async () => {
      try {
        const response = await fetch(`${normalizeBaseUrl(baseUrl)}/admin-api/component-photos/${encodeURIComponent(photoKey)}`, {
          credentials: "include", signal: controller.signal,
        });
        if (!response.ok) {
          if (active) setError(response.status === 404 ? "missing" : response.status === 401 ? "session" : "failed");
          return;
        }
        const blob = await response.blob();
        if (!blob.type.match(/^image\/(png|jpeg)$/) || blob.size > 5 * 1024 * 1024 || blob.size === 0) throw new Error("Invalid photo");
        if (!active) return;
        objectUrl = URL.createObjectURL(blob);
        setPhoto({ key: photoKey, url: objectUrl });
      } catch {
        if (active) setError("failed");
      } finally {
        window.clearTimeout(timeout);
      }
    })();
    return () => {
      active = false;
      controller.abort();
      window.clearTimeout(timeout);
      if (objectUrl) URL.revokeObjectURL(objectUrl);
    };
  }, [baseUrl, accountId, photoKey, attempt]);

  if (!photoKey) return null;
  const message = error === "missing" ? t("照片尚未上传到服务端，请在保存原图的设备上同步。")
    : error === "session" ? t("登录会话已失效。") : t("照片加载失败，请重试。");
  return <div className={compact ? "mt-2 max-w-32" : "mb-5 max-w-md"}>
    {photo?.key === photoKey ? <a href={photo.url} target="_blank" rel="noreferrer" aria-label={`${t("查看元件照片")}：${name}`}>
      <img src={photo.url} alt={`${t("元件照片")}：${name}`} className={compact ? "h-16 w-16 rounded-lg border bg-white object-contain" : "max-h-80 w-full rounded-xl border bg-white object-contain"} />
    </a> : error ? <div className="space-y-1 text-xs text-muted-foreground" role="status">
      <p>{message}</p><Button variant="ghost" size="sm" className="min-h-11" onClick={() => setAttempt(value => value + 1)}>{t("重试")}</Button>
    </div> : <div className={compact ? "h-16 w-16 animate-pulse rounded-lg bg-muted" : "h-40 animate-pulse rounded-xl bg-muted"} role="status" aria-label={t("正在加载照片…")} />}
  </div>;
}
