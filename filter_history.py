import sys

filepath = sys.argv[1]
outputpath = sys.argv[2]
filter_route = sys.argv[3]
fo = open(outputpath, 'w')

last_inquiry = ''
with open(filepath, 'r') as f:
	for line in f.readlines():
		history = line.split()
		if history[6] == filter_route:
			if history[3] == 'inquiry':
				last_inquiry = line
			else:
				fo.write(line)
fo.write(last_inquiry)
fo.close()
