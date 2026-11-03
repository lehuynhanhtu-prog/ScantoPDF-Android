from PIL import Image, ImageDraw

size = 256
im = Image.new("RGBA", (size, size), (12, 81, 190, 255))
d = ImageDraw.Draw(im)
d.rounded_rectangle((48, 55, 208, 108), radius=14, fill=(235,245,255), outline=(180,210,245), width=5)
d.polygon([(58,105),(198,105),(180,130),(76,130)], fill=(25,58,105))
d.rounded_rectangle((42, 112, 214, 195), radius=18, fill=(215,232,250), outline=(255,255,255), width=5)
d.rectangle((62,125,194,138), fill=(40,225,255))
d.rectangle((82,118,176,188), fill=(255,255,255))
for y in (135,149,163):
    d.rounded_rectangle((95,y,162,y+5), radius=2, fill=(90,155,225))
d.rounded_rectangle((158,154,230,226), radius=12, fill=(225,57,53))
d.text((171,176), "PDF", fill="white")
im.save("ScantoPDF.ico", sizes=[(16,16),(32,32),(48,48),(64,64),(128,128),(256,256)])
