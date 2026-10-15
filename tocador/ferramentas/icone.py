"""Gera res/icone.png (192x192): botão de play com barras de equalizador."""
import os
from PIL import Image, ImageDraw

L = 192 * 4  # desenha grande e reduz (bordas suaves)
img = Image.new("RGBA", (L, L), (0, 0, 0, 0))
d = ImageDraw.Draw(img)

# fundo arredondado com degradê
fundo = Image.new("RGBA", (L, L))
fd = ImageDraw.Draw(fundo)
for y in range(L):
    t = y / L
    cor = (int(28 + 10 * t), int(33 + 8 * t), int(44 + 12 * t), 255)
    fd.line([(0, y), (L, y)], fill=cor)
mascara = Image.new("L", (L, L), 0)
ImageDraw.Draw(mascara).rounded_rectangle([0, 0, L - 1, L - 1], radius=L * 0.22, fill=255)
img.paste(fundo, (0, 0), mascara)

# barras do equalizador
laranja = (245, 165, 36, 255)
amarelo = (255, 203, 107, 255)
alturas = [0.30, 0.52, 0.40, 0.66, 0.46]
base = L * 0.80
larg = L * 0.075
x0 = L * 0.20
for i, a in enumerate(alturas):
    x = x0 + i * L * 0.125
    d.rounded_rectangle([x, base - a * L * 0.62, x + larg, base], radius=larg / 2, fill=amarelo if i % 2 else laranja)

# play
cx, cy, r = L * 0.60, L * 0.40, L * 0.20
d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=(255, 255, 255, 255))
t = r * 0.55
d.polygon([(cx - t * 0.55, cy - t), (cx - t * 0.55, cy + t), (cx + t, cy)], fill=(30, 36, 48, 255))

saida = os.path.join(os.path.dirname(__file__), "..", "res", "icone.png")
os.makedirs(os.path.dirname(saida), exist_ok=True)
img.resize((192, 192), Image.LANCZOS).save(saida, optimize=True)
print("ícone:", os.path.abspath(saida))
