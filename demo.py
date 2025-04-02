from PIL import Image
import sys

input_file = sys.argv[1]
output_file = "bw_" + input_file

img = Image.open(input_file)
bw = img.convert('L')
bw.save(output_file)