import type { Metadata } from "next";
import Image from "next/image";
import Link from "next/link";
import { notFound } from "next/navigation";
import { newsMedia } from "@/content/news-media";
import { ui as strings } from "@/content/ui";
import { fetchNewsEntry } from "@/lib/api";
import { mediaSrc } from "@/lib/media";
import { pageMetadata } from "@/lib/seo";
import { JsonLd, articleStructuredData, breadcrumbStructuredData } from "@/lib/structured-data";
import { NewsGallery } from "./NewsGallery";
import styles from "./page.module.css";

// §8.3 плана: согласованный формат новости — заголовок, краткий анонс, полный
// текст, изображения, документы/ссылки и SEO. Страницы под материал не было
// вовсе: бэкенд отдавал /news/{slug} с текстом, админка текст принимала,
// а показать его сайту было негде — лента обрывалась на анонсе.
//
// Чего здесь пока нет: блока документов и ссылок к материалу. Под них нет
// модели, а придумывать её до согласования формата — угадывать. Оставлено
// вопросом к заказчику, а не заглушкой в разметке.
//
// Тело вынесено из `page.tsx` в `screen.tsx`, потому что `screen.tsx`
// маршрутом не является: здесь можно держать любые экспорты, чего
// `page.tsx` не позволяет. Сам текст материала пишет редактор в админке.

export async function newsEntryMetadata(slug: string): Promise<Metadata> {
  const entry = await fetchNewsEntry(slug);
  if (!entry) return {};

  // SEO собирается из заголовка и анонса. Отдельных полей title/description
  // у новости нет: §8.3 их называет, но формат ещё не согласован, и пока
  // анонс — честный источник описания, он для того и написан.
  return pageMetadata({
    title: `${entry.title} — VEDAL`,
    description: entry.excerpt,
    path: `/news/${entry.slug}/`,
    type: "article",
    publishedTime: entry.isoDate,
    image: entry.image
      ? { url: mediaSrc(entry.image.src), alt: entry.image.alt }
      : undefined,
  });
}

export default async function NewsEntryScreen({ slug }: { slug: string }) {
  const entry = await fetchNewsEntry(slug);
  // Неопубликованный материал для сайта не существует — 404, а не пустая
  // страница: по коду ответа не должно быть видно, что черновик есть.
  if (!entry) notFound();

  // Абзацы делятся пустой строкой. Разметку из текста не разбираем:
  // редактор пишет в админке обычный текст, и трактовать его как HTML
  // значило бы отдать вёрстку страницы тому, кто про неё не думает, —
  // и открыть вставку произвольной разметки.
  const paragraphs = (entry.body ?? "")
    .split(/\n\s*\n/)
    .map((p) => p.trim())
    .filter(Boolean);
  const path = `/news/${entry.slug}/`;
  const image = entry.image ? mediaSrc(entry.image.src) : undefined;
  const media = newsMedia[entry.slug];

  return (
    <main className={styles.page}>
      <JsonLd
        id={`breadcrumb-jsonld-news-${entry.slug}`}
        data={breadcrumbStructuredData(
          [
            { label: strings.crumbs.home, href: "/" },
            { label: strings.crumbs.news, href: "/news/" },
            { label: entry.title },
          ],
          path,
        )}
      />
      <JsonLd
        id={`article-jsonld-${entry.slug}`}
        data={articleStructuredData({
          path,
          title: entry.title,
          description: entry.excerpt,
          publishedTime: entry.isoDate,
          imageUrl: image,
        })}
      />
      <p className={styles.crumbs}>
        <Link href="/">{strings.crumbs.home}</Link> /{" "}
        <Link href="/news/">{strings.crumbs.news}</Link> /{" "}
        <span>{entry.tag}</span>
      </p>

      {/* Примечание стоит сразу после хлебных крошек — до заголовка:
          на этой странице первый же экран и есть текст материала,
          и объяснить кириллицу надо раньше, чем она встретится. */}

      <article className={styles.article}>
        <header className={styles.head}>
          <div className={styles.meta}>
            <span className={styles.tag}>
              {entry.tag}
            </span>
            {/* dateTime в ISO — дата для машин, текст рядом для людей. */}
            <time className={styles.date} dateTime={entry.isoDate}>
              {entry.date}
            </time>
          </div>
          <h1
            className={styles.title}
            data-words="34"
            data-wdelay="110"
          >
            {entry.title}
          </h1>
          <p className={styles.excerpt}>
            {entry.excerpt}
          </p>
        </header>

        {entry.image && (
          <div className={styles.photo}>
            <Image
              src={mediaSrc(entry.image.src)}
              alt={entry.image.alt}
              fill
              sizes="(max-width: 1100px) 100vw, 60vw"
              priority
            />
          </div>
        )}

        {paragraphs.length > 0 ? (
          <div className={styles.body}>
            {paragraphs.map((p) => (
              <p key={p}>{p}</p>
            ))}
          </div>
        ) : (
          <p className={styles.awaiting}>
            {BODY_AWAITING}
          </p>
        )}

        {/* Фото и видео сверх обложки — content/news-media.ts. Видео
            грузится по нажатию (preload="none"): два ролика по 8–10 МБ
            не должны скачиваться у каждого, кто открыл новость. */}
        {media && media.gallery.length > 0 && (
          <NewsGallery shots={media.gallery} />
        )}
        {media && media.videos.length > 0 && (
          <div className={styles.videos}>
            {media.videos.map((v) => (
              <figure key={v.src} className={styles.video}>
                <video
                  src={mediaSrc(v.src)}
                  poster={mediaSrc(v.poster)}
                  controls
                  playsInline
                  preload="none"
                  aria-label={v.title}
                />
                <figcaption>{v.title}</figcaption>
              </figure>
            ))}
          </div>
        )}
        {media?.credit && <p className={styles.credit}>{media.credit}</p>}
      </article>

      <div className={styles.back}>
        <Link href="/news/">{strings.actions.backToNews}</Link>
      </div>
    </main>
  );
}

// Состояние самого материала, а не интерфейса: строка говорит, что текст
// опубликован не целиком и ждёт согласования. «Ожидает уточнения» — термин
// проекта: он назван так же, как в правилах контента (CLAUDE.md).
const BODY_AWAITING =
  "Полный текст материала — ожидает уточнения. Опубликован анонс; развёрнутый текст появится после согласования.";
