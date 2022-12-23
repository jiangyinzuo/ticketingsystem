rm output.txt
for i in {1..100}; do
	echo $i
	timeout 60 ./verilin.sh >> output.txt
	if [ $? -ne 0 ];
	then
		exit
	fi
done