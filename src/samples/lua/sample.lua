local MAX_NUMBER = 100

-- Seed RNG with current time (and a little extra entropy on some Lua versions)
math.randomseed(os.time() + tonumber(tostring({}):match("%x+"), 16))

local function pick_secret()
  return math.random(1, MAX_NUMBER)
end

local function prompt(text)
  io.write(text)
  io.flush()
end

local function read_line()
  -- io.read returns nil on EOF (e.g., Ctrl+Z/Ctrl+D)
  return io.read("*l")
end

local function play_round()
  local secret = pick_secret()
  local attempts = 0

  print("\nI've picked a number between 1 and " .. MAX_NUMBER .. ". Can you guess it?")
  print("Type 'q' to quit the round.")

  while true do
    prompt("Your guess: ")
    local line = read_line()
    if not line then
      print("\nGoodbye!")
      return false
    end

    if line == "q" or line == "Q" or line == "quit" or line == "exit" then
      print("You gave up. The number was " .. secret .. ".")
      return true
    end

    local guess = tonumber(line)
    if not guess then
      print("Please enter a valid number, or 'q' to quit the round.")
    elseif guess < 1 or guess > MAX_NUMBER then
      print("Your guess must be between 1 and " .. MAX_NUMBER .. ".")
    else
      attempts = attempts + 1
      if guess < secret then
        print("Too low!")
      elseif guess > secret then
        print("Too high!")
      else
        print("Correct! You guessed it in " .. attempts .. (attempts == 1 and " try." or " tries."))
        return true
      end
    end
  end
end

print("=== Guess the Number (Lua) ===")
while true do
local finished = play_round()
if finished == false then break end

prompt("\nPlay again? (y/n): ")
local answer = read_line()
if not answer or answer:lower() ~= "y" then
  print("Bye!")
  break
end
end
