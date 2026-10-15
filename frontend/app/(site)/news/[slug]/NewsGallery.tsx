"use client";

import Image from "next/image";
import { useCallback, useEffect, useRef, useState } from "react";
import { createPortal } from "react-dom";
import { mediaSrc } from "@/lib/media";
import styles from "./page.module.css";

type GalleryShot = { src: string; alt: string };

export function NewsGallery({ shots }: { shots: GalleryShot[] }) {
  const [selected, setSelected] = useState<GalleryShot | null>(null);
  const closeButton = useRef<HTMLButtonElement>(null);
  const opener = useRef<HTMLButtonElement | null>(null);

  const close = useCallback(() => {
    setSelected(null);
    requestAnimationFrame(() => opener.current?.focus());
  }, []);

  useEffect(() => {
    if (!selected) return;

    const previousOverflow = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    closeButton.current?.focus();

    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape") {
        event.preventDefault();
        close();
      }
    };
    document.addEventListener("keydown", onKeyDown);

    return () => {
      document.body.style.overflow = previousOverflow;
      document.removeEventListener("keydown", onKeyDown);
    };
  }, [close, selected]);

  return (
    <>
      <ul className={styles.gallery}>
        {shots.map((shot) => (
          <li key={shot.src} className={styles.galleryItem}>
            <button
              type="button"
              className={styles.galleryButton}
              aria-label={`Увеличить изображение: ${shot.alt}`}
              onClick={(event) => {
                opener.current = event.currentTarget;
                setSelected(shot);
              }}
            >
              <Image
                src={mediaSrc(shot.src)}
                alt={shot.alt}
                fill
                quality={90}
                sizes="(max-width: 700px) 50vw, 380px"
              />
              <span className={styles.zoomHint}>Увеличить</span>
            </button>
          </li>
        ))}
      </ul>

      {selected && createPortal(
        <div
          className={styles.lightbox}
          role="dialog"
          aria-modal="true"
          aria-label={`Увеличенное изображение: ${selected.alt}`}
          onMouseDown={(event) => {
            if (event.target === event.currentTarget) close();
          }}
        >
          <div className={styles.lightboxImage}>
            <Image
              src={mediaSrc(selected.src)}
              alt={selected.alt}
              fill
              quality={90}
              sizes="100vw"
              priority
            />
          </div>
          <button
            ref={closeButton}
            type="button"
            className={styles.lightboxClose}
            aria-label="Закрыть увеличенное изображение"
            onClick={close}
          >
            ×
          </button>
        </div>,
        document.body,
      )}
    </>
  );
}
