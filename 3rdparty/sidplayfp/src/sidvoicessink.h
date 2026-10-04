/*
 * zxtune addition: access to separate voices output and chip state for visualization.
 */

#ifndef SIDVOICESSINK_H
#define SIDVOICESSINK_H

/**
 * Receiver of separate voices output and chip state snapshots.
 * Called from emulation thread.
 */
class SidVoicesSink
{
public:
    virtual ~SidVoicesSink() = default;

    /**
     * @param chip index of chip in order of locking (0 is the main one)
     * @param samples interleaved samples of 3 voices after filters
     * @param count samples count per voice
     */
    virtual void voices(unsigned int chip, const short* samples, unsigned int count) = 0;

    /**
     * Chip state snapshot, taken each statePeriod() output samples.
     * @param chip index of chip
     * @param regs last values written to registers 0x00..0x18
     * @param osc oscillator output (as OSC3 register) of each voice
     * @param env envelope output (as ENV3 register) of each voice
     */
    virtual void state(unsigned int chip, const unsigned char* regs, const unsigned char* osc, const unsigned char* env) = 0;

    /**
     * @return period of state snapshots in output samples
     */
    virtual unsigned int statePeriod() const = 0;
};

#endif // SIDVOICESSINK_H
