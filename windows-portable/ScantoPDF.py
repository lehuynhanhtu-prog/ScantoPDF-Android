import os
import tempfile
from dataclasses import dataclass
from pathlib import Path
from typing import Optional

import cv2
import numpy as np
from PIL import Image, ImageOps, ImageTk
import tkinter as tk
from tkinter import filedialog, messagebox, ttk
from reportlab.pdfgen import canvas as pdfcanvas
from reportlab.lib.pagesizes import A4, landscape

APP_NAME = "ScantoPDF"
SUPPORTED = [
    ("Ảnh", "*.jpg *.jpeg *.png *.webp *.bmp *.tif *.tiff"),
    ("JPEG", "*.jpg *.jpeg"),
    ("PNG", "*.png"),
    ("Tất cả", "*.*"),
]

@dataclass
class Page:
    path: str
    rotation: int = 0
    manual_corners: Optional[list[tuple[float, float]]] = None

def pil_from_path(path: str) -> Image.Image:
    im = Image.open(path)
    im = ImageOps.exif_transpose(im)
    if im.mode != "RGB":
        im = im.convert("RGB")
    return im.copy()

def pil_to_bgr(im: Image.Image) -> np.ndarray:
    return cv2.cvtColor(np.array(im), cv2.COLOR_RGB2BGR)

def order_points(pts: np.ndarray) -> np.ndarray:
    pts = np.asarray(pts, dtype=np.float32).reshape(4, 2)
    s = pts.sum(axis=1)
    d = np.diff(pts, axis=1).reshape(-1)
    tl = pts[np.argmin(s)]
    br = pts[np.argmax(s)]
    tr = pts[np.argmin(d)]
    bl = pts[np.argmax(d)]
    return np.array([tl, tr, br, bl], dtype=np.float32)

def detect_document_corners(im: Image.Image) -> Optional[list[tuple[float, float]]]:
    bgr = pil_to_bgr(im)
    h0, w0 = bgr.shape[:2]
    max_side = max(w0, h0)
    scale = min(1.0, 1500.0 / max_side)
    if scale < 1.0:
        bgr = cv2.resize(
            bgr,
            (max(1, int(w0 * scale)), max(1, int(h0 * scale))),
            interpolation=cv2.INTER_AREA,
        )

    h, w = bgr.shape[:2]
    image_area = float(w * h)
    gray = cv2.cvtColor(bgr, cv2.COLOR_BGR2GRAY)
    blur = cv2.GaussianBlur(gray, (5, 5), 0)
    edges = cv2.Canny(blur, 50, 150)
    kernel = cv2.getStructuringElement(cv2.MORPH_RECT, (7, 7))
    closed = cv2.morphologyEx(edges, cv2.MORPH_CLOSE, kernel)
    closed = cv2.dilate(closed, kernel)

    contours, _ = cv2.findContours(closed, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
    contours = sorted(contours, key=cv2.contourArea, reverse=True)[:12]

    best = None
    best_score = -1e9

    for contour in contours:
        area = cv2.contourArea(contour)
        if area / image_area < 0.28:
            continue
        perimeter = cv2.arcLength(contour, True)
        for epsilon in (0.012, 0.018, 0.024, 0.032, 0.045):
            approx = cv2.approxPolyDP(contour, epsilon * perimeter, True)
            if len(approx) != 4 or not cv2.isContourConvex(approx):
                continue

            quad = order_points(approx.reshape(4, 2))
            qarea = abs(cv2.contourArea(quad))
            qratio = qarea / image_area
            if qratio < 0.30:
                continue

            tl, tr, br, bl = quad
            top = np.linalg.norm(tr - tl)
            bottom = np.linalg.norm(br - bl)
            left = np.linalg.norm(bl - tl)
            right = np.linalg.norm(br - tr)
            avg_w = (top + bottom) / 2
            avg_h = (left + right) / 2
            if avg_w < w * 0.28 or avg_h < h * 0.28:
                continue

            ratio = avg_w / max(avg_h, 1.0)
            if ratio < 0.32 or ratio > 3.1:
                continue

            balance = min(top, bottom) / max(top, bottom) * min(left, right) / max(left, right)
            if balance < 0.38:
                continue

            minx, maxx = quad[:, 0].min(), quad[:, 0].max()
            miny, maxy = quad[:, 1].min(), quad[:, 1].max()
            coverage = ((maxx - minx) * (maxy - miny)) / image_area
            score = qratio * 5.0 + balance * 1.6 + coverage * 1.8

            if score > best_score:
                best_score = score
                best = quad.copy()

    if best is None:
        return None

    cx, cy = best[:, 0].mean(), best[:, 1].mean()
    best[:, 0] = np.clip(cx + (best[:, 0] - cx) * 1.018, 0, w - 1)
    best[:, 1] = np.clip(cy + (best[:, 1] - cy) * 1.018, 0, h - 1)

    return [(float(x / w), float(y / h)) for x, y in best]

def warp_document(im: Image.Image, normalized: list[tuple[float, float]]) -> Image.Image:
    w, h = im.size
    pts = np.array([(x * w, y * h) for x, y in normalized], dtype=np.float32)
    tl, tr, br, bl = order_points(pts)
    width_top = np.linalg.norm(tr - tl)
    width_bottom = np.linalg.norm(br - bl)
    height_left = np.linalg.norm(bl - tl)
    height_right = np.linalg.norm(br - tr)

    out_w = max(64, int(max(width_top, width_bottom)))
    out_h = max(64, int(max(height_left, height_right)))
    dst = np.array(
        [[0, 0], [out_w - 1, 0], [out_w - 1, out_h - 1], [0, out_h - 1]],
        dtype=np.float32,
    )
    matrix = cv2.getPerspectiveTransform(
        np.array([tl, tr, br, bl], dtype=np.float32),
        dst,
    )
    out = cv2.warpPerspective(
        pil_to_bgr(im),
        matrix,
        (out_w, out_h),
        flags=cv2.INTER_CUBIC,
        borderMode=cv2.BORDER_REPLICATE,
    )
    return Image.fromarray(cv2.cvtColor(out, cv2.COLOR_BGR2RGB))

def enhance_color_background(im: Image.Image) -> Image.Image:
    bgr = pil_to_bgr(im)
    channels = cv2.split(bgr)
    out_channels = []
    for channel in channels:
        background = cv2.GaussianBlur(channel, (0, 0), 25)
        normalized = cv2.divide(channel, background, scale=245)
        blended = cv2.addWeighted(normalized, 0.78, channel, 0.22, 0)
        out_channels.append(blended)
    merged = cv2.merge(out_channels)
    return Image.fromarray(cv2.cvtColor(merged, cv2.COLOR_BGR2RGB))

def prepare_page(page: Page, auto_crop: bool, mode: str, max_dim: int) -> Image.Image:
    im = pil_from_path(page.path)

    if page.manual_corners is not None:
        im = warp_document(im, page.manual_corners)
    elif auto_crop:
        detected = detect_document_corners(im)
        if detected:
            im = warp_document(im, detected)

    if page.rotation:
        im = im.rotate(-page.rotation, expand=True, resample=Image.Resampling.BICUBIC)

    scale = min(1.0, max_dim / max(im.size))
    if scale < 1.0:
        im = im.resize(
            (max(1, int(im.width * scale)), max(1, int(im.height * scale))),
            Image.Resampling.LANCZOS,
        )

    if mode == "Màu":
        im = enhance_color_background(im)
    elif mode == "Xám":
        im = ImageOps.grayscale(im).convert("RGB")
    elif mode == "Trắng đen (B&W)":
        gray = np.array(ImageOps.grayscale(im))
        _, bw = cv2.threshold(gray, 0, 255, cv2.THRESH_BINARY + cv2.THRESH_OTSU)
        im = Image.fromarray(bw).convert("RGB")
    else:
        im = im.convert("RGB")

    return im

class CornerEditor(tk.Toplevel):
    def __init__(self, parent, page: Page, on_apply):
        super().__init__(parent)
        self.title("Chỉnh 4 góc tài liệu")
        self.geometry("900x760")
        self.minsize(700, 600)
        self.transient(parent)
        self.grab_set()
        self.page = page
        self.on_apply = on_apply
        self.im = pil_from_path(page.path)

        detected = page.manual_corners or detect_document_corners(self.im)
        self.corners = [
            list(p)
            for p in (
                detected
                or [(0.03, 0.03), (0.97, 0.03), (0.97, 0.97), (0.03, 0.97)]
            )
        ]

        self.active = None
        self.photo = None
        self.draw_box = (0, 0, 1, 1)

        top = ttk.Frame(self, padding=8)
        top.pack(fill="x")
        ttk.Button(top, text="Thoát không lưu", command=self.destroy).pack(side="left", padx=4)
        ttk.Button(top, text="Dùng tự động", command=self.use_auto).pack(side="left", padx=4)
        ttk.Button(top, text="Áp dụng", command=self.apply).pack(side="left", padx=4)
        ttk.Label(
            top,
            text="Kéo 4 điểm tròn vào đúng 4 góc trang giấy.",
        ).pack(side="left", padx=16)

        self.canvas = tk.Canvas(self, bg="#181d23", highlightthickness=0)
        self.canvas.pack(fill="both", expand=True, padx=8, pady=(0, 8))
        self.canvas.bind("<Configure>", lambda e: self.redraw())
        self.canvas.bind("<Button-1>", self.down)
        self.canvas.bind("<B1-Motion>", self.move)
        self.canvas.bind("<ButtonRelease-1>", lambda e: setattr(self, "active", None))
        self.protocol("WM_DELETE_WINDOW", self.destroy)

    def redraw(self):
        cw = max(10, self.canvas.winfo_width())
        ch = max(10, self.canvas.winfo_height())
        scale = min(cw / self.im.width, ch / self.im.height)
        dw = max(1, int(self.im.width * scale))
        dh = max(1, int(self.im.height * scale))
        left = (cw - dw) / 2
        top = (ch - dh) / 2
        self.draw_box = (left, top, dw, dh)

        preview = self.im.resize((dw, dh), Image.Resampling.LANCZOS)
        self.photo = ImageTk.PhotoImage(preview)
        self.canvas.delete("all")
        self.canvas.create_image(left, top, image=self.photo, anchor="nw")

        pts = [(left + x * dw, top + y * dh) for x, y in self.corners]
        for i in range(4):
            x1, y1 = pts[i]
            x2, y2 = pts[(i + 1) % 4]
            self.canvas.create_line(x1, y1, x2, y2, fill="#00b4ff", width=4)
        for x, y in pts:
            r = 11
            self.canvas.create_oval(
                x-r, y-r, x+r, y+r,
                fill="white", outline="#006edc", width=4,
            )

    def screen_points(self):
        left, top, dw, dh = self.draw_box
        return [(left + x * dw, top + y * dh) for x, y in self.corners]

    def down(self, event):
        pts = self.screen_points()
        self.active = min(
            range(4),
            key=lambda i: (pts[i][0]-event.x)**2 + (pts[i][1]-event.y)**2,
        )

    def move(self, event):
        if self.active is None:
            return
        left, top, dw, dh = self.draw_box
        x = min(1.0, max(0.0, (event.x - left) / max(dw, 1)))
        y = min(1.0, max(0.0, (event.y - top) / max(dh, 1)))
        self.corners[self.active] = [x, y]
        self.redraw()

    def use_auto(self):
        self.page.manual_corners = None
        self.on_apply()
        self.destroy()

    def apply(self):
        self.page.manual_corners = [(float(x), float(y)) for x, y in self.corners]
        self.on_apply()
        self.destroy()

class ScantoPDFApp(tk.Tk):
    def __init__(self):
        super().__init__()
        self.title("ScantoPDF - Windows Portable")
        self.geometry("1060x760")
        self.minsize(900, 650)

        self.pages: list[Page] = []
        self.thumb_refs = []
        self.auto_crop = tk.BooleanVar(value=True)
        self.mode = tk.StringVar(value="Màu")
        self.quality = tk.StringVar(value="Cân bằng")

        self.build_ui()

    def build_ui(self):
        style = ttk.Style(self)
        try:
            style.theme_use("vista")
        except Exception:
            pass

        header = tk.Frame(self, bg="#123b69", padx=18, pady=14)
        header.pack(fill="x")
        tk.Label(
            header,
            text="ScantoPDF",
            fg="white",
            bg="#123b69",
            font=("Segoe UI", 22, "bold"),
        ).pack(side="left")
        tk.Label(
            header,
            text="  Quét ảnh và tài liệu thành PDF",
            fg="#dce8f7",
            bg="#123b69",
            font=("Segoe UI", 11),
        ).pack(side="left", pady=(8, 0))

        bar = ttk.Frame(self, padding=10)
        bar.pack(fill="x")
        ttk.Button(bar, text="Chọn ảnh mới", command=self.pick_images).pack(side="left", padx=4)
        ttk.Button(bar, text="Chụp webcam", command=self.capture_webcam).pack(side="left", padx=4)
        ttk.Button(bar, text="Xoay tất cả 90°", command=self.rotate_all).pack(side="left", padx=4)
        ttk.Button(bar, text="Tạo PDF", command=self.create_pdf).pack(side="left", padx=4)

        ttk.Checkbutton(
            bar,
            text="Tự cắt mép + nắn phối cảnh",
            variable=self.auto_crop,
            command=self.refresh_pages,
        ).pack(side="right", padx=8)

        settings = ttk.Frame(self, padding=(10, 0, 10, 8))
        settings.pack(fill="x")
        ttk.Label(settings, text="Màu:").pack(side="left")
        ttk.Combobox(
            settings,
            textvariable=self.mode,
            values=["Màu", "Xám", "Trắng đen (B&W)"],
            width=18,
            state="readonly",
        ).pack(side="left", padx=(4, 16))
        ttk.Label(settings, text="Chất lượng:").pack(side="left")
        ttk.Combobox(
            settings,
            textvariable=self.quality,
            values=["Nhẹ", "Cân bằng", "Sắc nét"],
            width=12,
            state="readonly",
        ).pack(side="left", padx=4)

        self.status = ttk.Label(settings, text="Chưa có trang nào")
        self.status.pack(side="right")

        outer = ttk.Frame(self)
        outer.pack(fill="both", expand=True, padx=10, pady=(0, 10))
        self.canvas = tk.Canvas(outer, bg="#f4f6f9", highlightthickness=0)
        scroll = ttk.Scrollbar(outer, orient="vertical", command=self.canvas.yview)
        self.inner = ttk.Frame(self.canvas)
        self.inner.bind(
            "<Configure>",
            lambda e: self.canvas.configure(scrollregion=self.canvas.bbox("all")),
        )
        self.window_id = self.canvas.create_window((0, 0), window=self.inner, anchor="nw")
        self.canvas.configure(yscrollcommand=scroll.set)
        self.canvas.pack(side="left", fill="both", expand=True)
        scroll.pack(side="right", fill="y")
        self.canvas.bind(
            "<Configure>",
            lambda e: self.canvas.itemconfig(self.window_id, width=e.width),
        )

    def pick_images(self):
        paths = filedialog.askopenfilenames(
            title="Chọn ảnh",
            filetypes=SUPPORTED,
        )
        if not paths:
            return
        # Giữ nguyên thứ tự mà hộp chọn file trả về, không tự sắp xếp theo tên/ngày.
        self.pages = [Page(p) for p in paths]
        self.refresh_pages()

    def capture_webcam(self):
        cap = cv2.VideoCapture(0, cv2.CAP_DSHOW)
        if not cap.isOpened():
            messagebox.showerror(APP_NAME, "Không mở được webcam.")
            return

        messagebox.showinfo(
            APP_NAME,
            "Cửa sổ camera sẽ mở. Nhấn SPACE để chụp, ESC để hủy.",
        )

        captured = None
        while True:
            ok, frame = cap.read()
            if not ok:
                break
            cv2.putText(
                frame,
                "SPACE: capture   ESC: cancel",
                (20, 35),
                cv2.FONT_HERSHEY_SIMPLEX,
                0.8,
                (0, 255, 0),
                2,
            )
            cv2.imshow("ScantoPDF Camera", frame)
            key = cv2.waitKey(1) & 0xFF
            if key == 27:
                break
            if key == 32:
                captured = frame.copy()
                break

        cap.release()
        cv2.destroyAllWindows()

        if captured is not None:
            folder = Path(tempfile.gettempdir()) / "ScantoPDF"
            folder.mkdir(parents=True, exist_ok=True)
            path = folder / f"capture_{len(self.pages)+1}.jpg"
            cv2.imwrite(str(path), captured)
            self.pages.append(Page(str(path)))
            self.refresh_pages()

    def rotate_all(self):
        for page in self.pages:
            page.rotation = (page.rotation + 90) % 360
        self.refresh_pages()

    def page_preview(self, page: Page, max_size=(520, 280)):
        im = pil_from_path(page.path)
        try:
            if page.manual_corners:
                im = warp_document(im, page.manual_corners)
            elif self.auto_crop.get():
                pts = detect_document_corners(im)
                if pts:
                    im = warp_document(im, pts)
        except Exception:
            pass

        if page.rotation:
            im = im.rotate(
                -page.rotation,
                expand=True,
                resample=Image.Resampling.BICUBIC,
            )

        im.thumbnail(max_size, Image.Resampling.LANCZOS)
        return im

    def refresh_pages(self):
        for widget in self.inner.winfo_children():
            widget.destroy()
        self.thumb_refs.clear()

        for idx, page in enumerate(self.pages):
            card = ttk.LabelFrame(
                self.inner,
                text=f"Trang {idx+1}",
                padding=8,
            )
            card.pack(fill="x", padx=8, pady=7)

            try:
                im = self.page_preview(page)
                photo = ImageTk.PhotoImage(im)
                self.thumb_refs.append(photo)
                ttk.Label(card, image=photo).pack(side="left", padx=(0, 14))
            except Exception as exc:
                ttk.Label(
                    card,
                    text=f"Không đọc được ảnh: {exc}",
                    width=45,
                ).pack(side="left", padx=(0, 14))

            actions = ttk.Frame(card)
            actions.pack(side="left", fill="y")
            ttk.Button(
                actions,
                text="Chỉnh 4 góc / Cắt mép",
                command=lambda p=page: CornerEditor(self, p, self.refresh_pages),
            ).pack(fill="x", pady=3)
            order_row = ttk.Frame(actions)
            order_row.pack(fill="x", pady=3)
            up = ttk.Button(
                order_row,
                text="↑ Lên",
                command=lambda p=page: self.move_page(p, -1),
            )
            up.pack(side="left", fill="x", expand=True, padx=(0, 2))
            down = ttk.Button(
                order_row,
                text="↓ Xuống",
                command=lambda p=page: self.move_page(p, 1),
            )
            down.pack(side="left", fill="x", expand=True, padx=(2, 0))
            if idx == 0:
                up.state(["disabled"])
            if idx == len(self.pages) - 1:
                down.state(["disabled"])

            ttk.Button(
                actions,
                text="Xoay trang 90°",
                command=lambda p=page: self.rotate_one(p),
            ).pack(fill="x", pady=3)
            ttk.Button(
                actions,
                text="Xóa trang",
                command=lambda p=page: self.delete_page(p),
            ).pack(fill="x", pady=3)

            if page.manual_corners:
                ttk.Label(actions, text="Đang dùng góc chỉnh tay").pack(pady=(8, 0))

        count = len(self.pages)
        if count == 0:
            self.status.config(text="Chưa có trang nào")
        else:
            self.status.config(text=f"{count} trang đã sẵn sàng")

    def move_page(self, page, delta):
        if page not in self.pages:
            return
        old_index = self.pages.index(page)
        new_index = old_index + delta
        if new_index < 0 or new_index >= len(self.pages):
            return
        self.pages.pop(old_index)
        self.pages.insert(new_index, page)
        self.refresh_pages()

    def rotate_one(self, page):
        page.rotation = (page.rotation + 90) % 360
        self.refresh_pages()

    def delete_page(self, page):
        if page in self.pages:
            self.pages.remove(page)
            self.refresh_pages()

    def create_pdf(self):
        if not self.pages:
            messagebox.showwarning(
                APP_NAME,
                "Hãy chọn ảnh hoặc chụp tài liệu trước.",
            )
            return

        path = filedialog.asksaveasfilename(
            title="Lưu PDF",
            defaultextension=".pdf",
            initialfile="ScantoPDF.pdf",
            filetypes=[("PDF", "*.pdf")],
        )
        if not path:
            return

        max_dim = {
            "Nhẹ": 1300,
            "Cân bằng": 1900,
            "Sắc nét": 2600,
        }[self.quality.get()]
        mode = self.mode.get()
        auto = self.auto_crop.get()

        progress = tk.Toplevel(self)
        progress.title("Đang tạo PDF")
        progress.transient(self)
        ttk.Label(
            progress,
            text="Đang xử lý tài liệu...",
            padding=16,
        ).pack()
        pb = ttk.Progressbar(
            progress,
            length=360,
            maximum=len(self.pages),
        )
        pb.pack(padx=16, pady=(0, 16))
        progress.update()

        temp_files = []
        try:
            pdf = pdfcanvas.Canvas(path)

            for idx, page in enumerate(self.pages):
                progress.title(
                    f"Đang tạo trang {idx+1}/{len(self.pages)}"
                )
                im = prepare_page(
                    page,
                    auto,
                    mode,
                    max_dim,
                )

                page_size = landscape(A4) if im.width > im.height else A4
                page_w, page_h = page_size
                pdf.setPageSize(page_size)

                tmp = tempfile.NamedTemporaryFile(
                    delete=False,
                    suffix=".jpg",
                )
                tmp.close()
                im.save(
                    tmp.name,
                    "JPEG",
                    quality=92,
                    optimize=True,
                )
                temp_files.append(tmp.name)

                scale = min(
                    page_w / im.width,
                    page_h / im.height,
                )
                draw_w = im.width * scale
                draw_h = im.height * scale
                x = (page_w - draw_w) / 2
                y = (page_h - draw_h) / 2

                pdf.drawImage(
                    tmp.name,
                    x,
                    y,
                    width=draw_w,
                    height=draw_h,
                    preserveAspectRatio=True,
                    mask="auto",
                )
                pdf.showPage()

                pb["value"] = idx + 1
                progress.update()

            pdf.save()
            progress.destroy()
            messagebox.showinfo(
                APP_NAME,
                "Đã tạo PDF thành công.\n\nĐường dẫn:\n"
                + os.path.abspath(path),
            )

        except Exception as exc:
            progress.destroy()
            messagebox.showerror(
                APP_NAME,
                "Không thể tạo PDF:\n" + str(exc),
            )

        finally:
            for temp_path in temp_files:
                try:
                    os.unlink(temp_path)
                except OSError:
                    pass

def main():
    app = ScantoPDFApp()
    app.mainloop()

if __name__ == "__main__":
    main()
